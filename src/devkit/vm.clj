(ns devkit.vm
  "The dev VM: one podman container running Arch Linux with systemd as PID 1,
  IntelliJ IDEA on an xpra display that is served as HTML5 on 127.0.0.1.

  Only podman and babashka are needed on the host. On a Mac every podman call
  goes to the podman machine, so paths given to the container (the work folder
  and the podman socket) are paths inside that machine."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def root (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))
(def cache (str root "/.scratch/cache"))
(def ctx (str root "/.scratch/ctx"))
(def ca-dir (str root "/.scratch/ca"))

(def container "devkit")
(def image "localhost/devkit:latest")
(def network "devkit")
(def home-volume "devkit-home")
(def port 14500)
(def idea-version "2026.3")

(def arm? (#{"aarch64" "arm64"} (System/getProperty "os.arch")))

(defn sh
  "Run a command, inherit output, throw (with the command in the message) on failure."
  [& args]
  (let [r @(apply p/process {:inherit true} args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "command failed (exit " (:exit r) "): " (str/join " " (take 4 args)) " ...") {})))
    nil))

(defn cli
  "Run a task function; a refused or failed action prints one line and exits 1, no stack trace."
  [f & args]
  (try (apply f args)
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println "error:" (ex-message e)))
         (System/exit 1))))

(defn out
  "Run a command, return trimmed stdout, or nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn opt
  "Value after FLAG in args, or default."
  [args flag default]
  (let [m (zipmap args (rest args))] (get m flag default)))

(defn machine?
  "True when podman talks to a podman machine (a Mac, or Linux with a machine)."
  [] (boolean (some-> (out "podman" "machine" "list" "--format" "{{.Name}}") not-empty)))

(declare ensure-subids!)
(declare machine-start!)
(defn ensure-podman! []
  (when-not (out "podman" "info")
    (if (machine?)
      (machine-start!)
      (throw (ex-info "podman does not answer" {}))))
  (ensure-subids!))

(defn ensure-subids!
  "A podman machine whose user has no subuid range can map only one user into a
  container, so image layers with other owners cannot be unpacked. Give it a range."
  []
  (when (machine?)
    (let [m (out "podman" "machine" "ssh" "podman unshare cat /proc/self/uid_map")]
      (when (and m (= 1 (count (str/split-lines m))))
        (println "podman machine has no subuid range; adding one")
        (sh "podman" "machine" "ssh" "sudo usermod --add-subuids 100000-165535 --add-subgids 100000-165535 $(id -un); podman system migrate")))))

(defn sha256 [f]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (java.io.FileInputStream. (str f))]
      (let [buf (byte-array 1048576)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn download!
  "curl URL into FILE unless it is there; resumable."
  [url file]
  (fs/create-dirs (fs/parent file))
  (when-not (fs/exists? file)
    (let [part (str file ".part")]
      (sh "curl" "-fL" "--no-progress-meter" "--retry" "5" "-C" "-" "-o" part url)
      (fs/move part file))))

(defn base-image!
  "Arch Linux base. x86_64: the official image. arm64: the Arch Linux ARM
  rootfs tarball (the official image has no arm64), md5-checked and imported."
  []
  (if-not arm?
    "docker.io/library/archlinux:latest"
    (let [ref "localhost/devkit-base:alarm"]
      (when-not (out "podman" "image" "exists" ref)
        (let [url "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
              f (str cache "/ArchLinuxARM-aarch64-latest.tar.gz")
              want (-> (out "curl" "-fsSL" (str url ".md5")) (str/split #"\s+") first)]
          (download! url f)
          (let [md (MessageDigest/getInstance "MD5")
                got (with-open [in (java.io.FileInputStream. f)]
                      (let [buf (byte-array 1048576)]
                        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))
                        (apply str (map #(format "%02x" %) (.digest md)))))]
            (when-not (= want got) (fs/delete f) (throw (ex-info "ALARM tarball md5 mismatch" {:want want :got got}))))
          (sh "podman" "import" "--arch" "arm64" "--os" "linux" f ref)))
      ref)))

(defn idea-tarball! []
  (let [a (if arm? "-aarch64" "")
        url (str "https://download.jetbrains.com/idea/idea-" idea-version a ".tar.gz")
        f (str cache "/idea-" idea-version a ".tar.gz")]
    (download! url f)
    (let [want (-> (out "curl" "-fsSL" (str url ".sha256")) (str/split #"\s+") first)]
      (when-not (= want (sha256 f)) (fs/delete f) (throw (ex-info "IDEA sha256 mismatch" {:want want}))))
    f))

(defn plugins-dir
  "Where the plugin zips come from: --plugins, $DEVKIT_PLUGINS, .scratch/artifacts (the monorepo build's
  output, with manifest.edn), else .scratch/plugins."
  [args]
  (or (opt args "--plugins" nil)
      (System/getenv "DEVKIT_PLUGINS")
      (let [a (str root "/.scratch/artifacts")] (when (seq (fs/glob a "*.zip")) a))
      (str root "/.scratch/plugins")))

(defn build!
  "Assemble .scratch/ctx (Containerfile, files, plugins, CAs, IDE tarball) and build the image."
  [args]
  (ensure-podman!)
  (let [plugins (plugins-dir args)
        zips (fs/glob plugins "*.zip")
        base (base-image!)
        tar (idea-tarball!)]
    (when (empty? zips) (throw (ex-info (str "no plugin zips in " plugins) {})))
    (fs/delete-tree ctx)
    (doseq [d [(str ctx "/plugins") (str ctx "/ca") ca-dir]] (fs/create-dirs d))
    (fs/copy (str root "/image/Containerfile") ctx)
    (fs/copy-tree (str root "/image/files") (str ctx "/files"))
    (doseq [z zips] (println "plugin" (str (fs/file-name z)) "from" (str plugins)) (fs/unzip z (str ctx "/plugins")))
    (doseq [c (fs/glob ca-dir "*.pem")] (fs/copy c (str ctx "/ca")))
    (spit (str ctx "/ca/.keep") "")
    (fs/create-link (str ctx "/idea.tar.gz") tar)
    (sh "podman" "build" "--build-arg" (str "BASE=" base) "-t" image ctx)))

(defn socket-path
  "Path of the podman API socket as seen by the machine (or the Linux host)."
  []
  (let [s (str/replace (out "podman" "info" "--format" "{{.Host.RemoteSocket.Path}}") #"^unix://" "")]
    (when (and (not (machine?)) (str/starts-with? s "/run/user"))
      (out "systemctl" "--user" "start" "podman.socket"))
    s))

(def repos-mount "/repos")
(def default-repos (str root "/.scratch/repos"))
(def work-volume "devkit-work")
(def settings-file (str root "/.scratch/settings.edn"))

(defn settings
  "What `bb vm:up` or the menu chose last: {:work DIR-or-nil :repos DIR}."
  []
  (try (read-string (slurp settings-file)) (catch Exception _ {})))

(defn save-settings! [m]
  (fs/create-dirs (fs/parent settings-file))
  (spit settings-file (pr-str (merge (settings) m))))

(defn url [] (str "http://127.0.0.1:" port "/"))

(defn answers?
  "True when the page really answers."
  [] (= "200" (out "curl" "-fsS" "-m" "2" "-o" "/dev/null" "-w" "%{http_code}" (url))))

(defn machine-running? []
  (some #{"true"} (some-> (out "podman" "machine" "list" "--format" "{{.Running}}") str/split-lines)))

(defn container-state
  "nil when there is no container, else podman's word (running, exited, ...). :error when podman does not answer."
  []
  (let [r (p/shell {:out :string :err :string :continue true} "podman" "container" "inspect" "-f" "{{.State.Status}}" container)]
    (cond (zero? (:exit r)) (str/trim (:out r))
          (re-find #"(?i)no such|no container|not found|does not exist" (:err r)) nil
          :else :error)))

(defn running? [] (= "running" (container-state)))

(defn probe
  "The truth about the VM now: {:state S :words TEXT ...}. S is one of
  :no-podman :machine-stopped :not-built :stopped :starting :running :broken."
  []
  (cond
    (and (machine?) (not (machine-running?)))
    {:state :machine-stopped :words "the podman machine is stopped"}
    :else
    (let [cs (container-state)]
      (cond
        (= cs :error) {:state :no-podman :words "podman does not answer"}
        (nil? cs) (if (out "podman" "image" "exists" image)
                    {:state :stopped :words "not running"}
                    {:state :not-built :words "the image is not built yet"})
        (not= cs "running") {:state :stopped :words (str "stopped (the container is " cs ", it was killed or crashed)")}
        :else
        (let [units (zipmap ["xpra" "idea"]
                            (some-> (out "podman" "exec" container "systemctl" "is-active" "xpra" "idea") str/split-lines))
              ok (answers?)
              mem (out "podman" "stats" "--no-stream" "--format" "{{.MemUsage}}" container)]
          (cond ok {:state :running :words "the page answers" :units units :mem mem}
                (every? #{"active"} (vals units)) {:state :starting :words "starting, the page does not answer yet" :units units :mem mem}
                :else {:state :broken :words (str "BROKEN: the page does not answer, " (pr-str units)) :units units :mem mem}))))))

(defn machine-start! []
  (if (machine-running?)
    (println "the podman machine is already running")
    (do (println "starting the podman machine (a minute)")
        (if (out "podman" "machine" "start") (println "the podman machine is running") (throw (ex-info "the podman machine did not start; try `podman machine start` yourself" {}))))))

(defn up!
  "Start the VM (home volume, work folder, repos folder, CA folder, host podman socket) and wait for the page.
  --work DIR and --repos DIR are remembered in .scratch/settings.edn. Already running: says so and does nothing."
  [args]
  (ensure-podman!)
  (let [w (opt args "--work" ::none) r (opt args "--repos" nil)]
    (when (or (not= w ::none) r)
      (save-settings! (cond-> {} (not= w ::none) (assoc :work w) r (assoc :repos r)))))
  (when-not (out "podman" "image" "exists" image) (throw (ex-info "the image is not built; run bb vm:build (or Build in the menu)" {})))
  (let [{:keys [state]} (probe)]
    (if (= state :running)
      (println "already running at" (url) "- nothing changed")
      (let [s (settings)
            work (if-let [w (:work s)] (str (fs/canonicalize (fs/create-dirs w))) work-volume)
            repos (str (fs/canonicalize (fs/create-dirs (or (:repos s) default-repos))))]
        (fs/create-dirs ca-dir)
        (when-not (= state :starting)
          (out "podman" "rm" "-f" container)
          (println "work " (if (= work work-volume) "podman volume devkit-work" work) "-> /work")
          (println "repos" repos "-> /repos")
          (println "starting; the first start after a build copies the image and can take minutes")
          (sh "podman" "run" "-d" "--name" container "--hostname" "devkit"
              "--systemd=always" "--userns=keep-id:uid=1000,gid=1000" "--user" "root"
              "--security-opt" "label=disable" "--shm-size" "1g" "--network" "pasta:-T,4566,-T,5432,-T,8000-8999"
              "-p" (str "127.0.0.1:" port ":" port)
              "-v" (str home-volume ":/home/dev")
              "-v" (str work ":/work")
              "-v" (str repos ":" repos-mount)
              "-v" (str ca-dir ":/ca:ro")
              "-v" (str (socket-path) ":/run/user/1000/podman/podman.sock")
              image))
        (println "waiting for the page")
        (loop [i 0]
          (cond (answers?) nil
                (> i 90) (throw (ex-info "the page did not answer in 3 minutes; see bb vm:logs" {}))
                :else (do (Thread/sleep 2000) (recur (inc i)))))
        (println "open" (url))))))

(defn down!
  "Stop and remove the container (volumes stay). Says so when there is none."
  []
  (if (or (and (machine?) (not (machine-running?))) (nil? (container-state)))
    (println "already stopped - nothing to do")
    (do (out "podman" "stop" "-t" "30" container)
        (out "podman" "rm" "-f" container)
        (println "stopped (home and work volumes kept)"))))

(defn status!
  "The true state, in words; the URL only when the page answers."
  []
  (let [{:keys [state words units mem]} (probe)]
    (println (str/upper-case (name state)) "-" words)
    (when (#{:running :starting :broken} state)
      (when (= state :running) (println "url:   " (url)))
      (println "units: " (pr-str units))
      (println "memory:" mem)
      (println "work:  " (out "podman" "inspect" "-f" "{{range .Mounts}}{{if eq .Destination \"/work\"}}{{.Name}}{{.Source}}{{end}}{{end}}" container) "-> /work")
      (println "repos: " (out "podman" "inspect" "-f" "{{range .Mounts}}{{if eq .Destination \"/repos\"}}{{.Source}}{{end}}{{end}}" container) "-> /repos"))
    (case state
      :machine-stopped (println "start it: bb vm (menu) or `podman machine start`")
      :not-built (println "build it: bb vm:build")
      :stopped (println "start it: bb vm:up")
      nil)))

(defn shell! []
  (when-not (running?) (throw (ex-info "the VM is not running: start it first" {})))
  (sh "podman" "exec" "-it" "-u" "dev" "-w" "/work" container "bash" "-l"))

(defn logs! [args]
  (when-not (running?) (throw (ex-info "the VM is not running: start it first" {})))
  (apply sh "podman" "exec" container "journalctl" "-u" "idea" "-u" "xpra" "-u" "devkit-ca" "-n" "200" "--no-pager"
         (when (some #{"-f"} args) ["-f"])))

(defn- pem-files
  "The PEM/CRT files named by the arguments: a file, or every .pem/.crt/.cer in a folder."
  [paths]
  (vec (distinct (mapcat (fn [p] (cond (fs/directory? p) (filter #(re-find #"(?i)\.(pem|crt|cer)$" (str %)) (fs/list-dir p))
                                       (fs/exists? p) [(fs/path p)]
                                       :else (throw (ex-info (str "no such file: " p) {}))))
                         paths))))

(defn ca-install!
  "Install corporate root CAs given as PEM files (or folders of them). Only the paths given are read;
  the macOS keychain is never touched. Each is kept in .scratch/ca (mounted at /ca, baked into the next
  build), put in the podman machine's trust store so image pulls work, and installed in a running VM by
  devkit-ca (system store, and so every Java store). Files already installed unchanged are skipped."
  [args]
  (let [files (pem-files args)]
    (when (empty? files) (throw (ex-info "usage: bb ca:install FILE.pem|DIR ..." {})))
    (ensure-podman!)
    (fs/create-dirs ca-dir)
    (let [changed (doall
                    (for [f files
                          :let [txt (slurp (str f))
                                base (str/replace (str (fs/file-name f)) #"(?i)\.(pem|crt|cer)$" "")
                                dest (str ca-dir "/" base ".pem")]
                          :when (do (when-not (str/includes? txt "-----BEGIN CERTIFICATE-----")
                                      (throw (ex-info (str f " is not a PEM certificate") {})))
                                    (not (and (fs/exists? dest) (= txt (slurp dest)))))]
                      (do (spit dest txt) {:name (str base ".pem") :dest dest})))]
      (if (empty? changed)
        (println "nothing new: all" (count files) "file(s) already installed")
        (do (doseq [{:keys [name dest]} changed]
              (println "kept" dest)
              (when (machine?)
                (-> (p/process {:in (fs/file dest) :inherit :err :out :inherit}
                               "podman" "machine" "ssh" (str "sudo tee /etc/pki/ca-trust/source/anchors/" name " >/dev/null"))
                    p/check)))
            (if (machine?)
              (do (sh "podman" "machine" "ssh" "sudo update-ca-trust; systemctl --user stop podman.service podman.socket; systemctl --user start podman.socket; true")
                  (println "podman machine trusts them"))
              (println "no podman machine: this host's own trust store is used for pulls; add the CAs there if pulls fail"))
            (when (running?)
              (sh "podman" "exec" container "systemctl" "restart" "devkit-ca")
              (println "running VM updated")))))))

(defn checkout!
  "Make the working copy /work/NAME from the bare repository /repos/NAME.git (its origin) unless it exists."
  [name]
  (when-not (running?) (throw (ex-info "the VM is not running" {})))
  (let [r (p/shell {:continue true :out :string :err :string} "podman" "exec" "-u" "dev" container "bash" "-lc"
                   (str "cd /work && if [ -d '" name "/.git' ]; then echo 'already there: /work/" name "'; "
                        "else git clone /repos/" name ".git " name " && echo 'cloned /repos/" name ".git to /work/" name "'; fi"))]
    (print (:out r) (:err r)) (flush)
    (when-not (zero? (:exit r)) (throw (ex-info "checkout failed" {})))))
