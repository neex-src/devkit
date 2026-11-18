(ns devkit.vm
  "The dev VM: one podman container running Arch Linux with systemd as PID 1,
  IntelliJ IDEA on an xpra display that is served as HTML5 on 127.0.0.1.

  Only podman and babashka are needed on the host. On a Mac every podman call
  goes to the podman machine, so paths given to the container (the work folder
  and the podman socket) are paths inside that machine.

  The work folder is a folder of the host, mounted in the VM at ITS OWN PATH (/work is a link to it).
  `podman` inside the VM is the host's podman, so a container a tool starts from the VM (Floci, Postgres,
  a service) runs next to the VM, and the files it mounts are looked up on the host: only a path that is
  the same on both sides works.

  `snapshot` is everything the menu shows, read in one go; the `bb vm:*` tasks and the menu call the
  same functions. What is kept between runs is in the state folder (.scratch, or $DEVKIT_STATE)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def root (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))
(def state
  "Where downloads, CAs, settings and the default folders live: $DEVKIT_STATE, else .scratch of this checkout."
  (or (not-empty (System/getenv "DEVKIT_STATE")) (str root "/.scratch")))
(def cache (str state "/cache"))
(def ctx (str state "/ctx"))
(def ca-dir (str state "/ca"))
(def logs-dir (str state "/logs"))
(def default-repos (str state "/repos"))
(def default-work (str state "/work"))
(def settings-file (str state "/settings.edn"))

(def container "devkit")
(def image "localhost/devkit:latest")
(def home-volume "devkit-home")
(def port 14500)
(def idea-version "2026.3")
(def repos-mount "/repos")
(def network
  "pasta with -T auto: every port that listens on the host's loopback answers on the VM's loopback too
  (pasta looks every second, and leaves out the port it forwards the other way, 14500). So whatever a
  container next to the VM publishes (Floci 4566, a database, a service, on any port a tool picked) is
  at localhost:PORT inside the VM, as it is on the host.
  -4: IPv4 only. With IPv6 on, pasta also listens on ::1, where `localhost` goes first; a container
  published on 127.0.0.1 has nothing on the host's ::1, so the connection opens and is then reset, and
  curl, Go and terraform do not try 127.0.0.1 after that. With -4 ::1 refuses at once and they do."
  "pasta:-4,-T,auto")

(def arm? (#{"aarch64" "arm64"} (System/getProperty "os.arch")))
(def mac? (boolean (re-find #"(?i)mac" (System/getProperty "os.name"))))

;; ---- running things ---------------------------------------------------------------------------------------

(defn sh
  "Run a command; its output goes line by line to *out* (the menu shows it as progress and keeps it in a
  log). Throws, with the command in the message, when it fails."
  [& args]
  (let [proc (apply p/process {:err :out} args)]
    (with-open [r (io/reader (:out proc))]
      (doseq [l (line-seq r)] (println l)))
    (let [exit (:exit @proc)]
      (when-not (zero? exit)
        (throw (ex-info (str "command failed (exit " exit "): " (str/join " " (take 4 args)) " ...") {})))
      nil)))

(declare run)

(defn run-quiet!
  "Run a command whose output says nothing (a container id); when it fails, throw WHAT and its last line."
  [what & args]
  (let [r (apply run 1800000 args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str what ": " (last (remove str/blank? (str/split-lines (str (:out r) (:err r)))))) {})))
    nil))

(defn tty
  "Run a command on the terminal (a shell, a pager); its exit code."
  [& args]
  (:exit @(apply p/process {:inherit true} args)))

(defn run
  "Run quietly: {:exit :out :err}. Exit -1 when it took longer than ms."
  [ms & args]
  (let [proc (apply p/process {:out :string :err :string} args)
        r (deref proc ms ::late)]
    (if (= r ::late)
      (do (p/destroy-tree proc) {:exit -1 :out "" :err "no answer in time"})
      r)))

(defn out
  "Run a command, return trimmed stdout, or nil when it fails."
  [& args]
  (let [r (apply run 120000 args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn cli
  "Run a task function; a refused or failed action prints one line and exits 1, no stack trace."
  [f & args]
  (try (apply f args)
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println "error:" (ex-message e)))
         (System/exit 1))))

(defn opt
  "Value after FLAG in args, or default."
  [args flag default]
  (let [m (zipmap args (rest args))] (get m flag default)))

(defn- fail [& words] (throw (ex-info (str/join " " words) {})))

;; ---- settings ---------------------------------------------------------------------------------------------

(defn settings
  "What was chosen last: {:work DIR :repos DIR :desktop \"xfce\"|\"i3\" :hide REGEX (repos not listed)}."
  []
  (try (edn/read-string (slurp settings-file)) (catch Exception _ {})))

(defn save-settings! [m]
  (fs/create-dirs (fs/parent settings-file))
  (spit settings-file (pr-str (merge (settings) m))))

(defn work-dir [] (str (fs/absolutize (or (:work (settings)) default-work))))
(defn repos-dir [] (str (fs/absolutize (or (:repos (settings)) default-repos))))
(defn desktop [] (if (= "i3" (:desktop (settings))) "i3" "xfce"))

;; ---- podman, the podman machine ---------------------------------------------------------------------------

(defn podman? [] (some? (fs/which "podman")))

(defn machine
  "The podman machine podman talks to, or nil when there is none (plain Linux):
  {:name :running? :starting? :cpus :memory (bytes) :disk (bytes)}."
  []
  (let [r (run 20000 "podman" "machine" "list" "--format" "json")
        ms (when (zero? (:exit r)) (try (json/parse-string (:out r) true) (catch Exception _ nil)))
        m (or (some #(when (:Default %) %) ms) (first ms))]
    (when m
      {:name (:Name m) :running? (boolean (:Running m)) :starting? (boolean (:Starting m))
       :cpus (:CPUs m)
       :memory (some-> (:Memory m) str parse-long)
       :disk (some-> (:DiskSize m) str parse-long)})))

(defn machine? [] (some? (machine)))
(defn machine-running? [] (boolean (:running? (machine))))

(declare ensure-subids!)

(defn- detached
  "The command, run in a session of its own. `podman machine start` leaves the machine's helpers (vfkit,
  gvproxy) in the session of whoever started it: started from the menu, they died with the terminal the
  menu ran in, and the machine and the VM with them. setsid where there is one (Linux), perl's on a Mac."
  [cmd]
  (cond (fs/which "setsid") (into ["setsid" "-w"] cmd)
        (fs/which "perl") (into ["perl" "-MPOSIX" "-e" "POSIX::setsid(); exec @ARGV or die qq(exec: $!)" "--"] cmd)
        :else cmd))

(defn machine-start! []
  (let [m (machine)]
    (cond (nil? m) (fail "there is no podman machine; make one: podman machine init")
          (:running? m) (println "the podman machine is already running")
          :else (do (println "starting the podman machine (about a minute)")
                    (apply sh (detached ["podman" "machine" "start"]))
                    (println "the podman machine is running")))))

(defn machine-stop! []
  (if (machine-running?)
    (do (println "stopping the podman machine (the VM and every container in it stop)")
        (sh "podman" "machine" "stop")
        (println "the podman machine is stopped"))
    (println "the podman machine is not running")))

(defn machine-init!
  "A podman machine with room for the IDE and a few services."
  []
  (when (machine) (fail "there is a podman machine already"))
  (println "making a podman machine: 6 CPUs, 12 GB memory, 80 GB disk (a download, a few minutes)")
  (sh "podman" "machine" "init" "--cpus" "6" "--memory" "12288" "--disk-size" "80")
  (machine-start!))

(defn machine-resize!
  "Other CPUs and memory (GB) for the podman machine; it is stopped and started."
  [cpus gb]
  (let [m (machine)]
    (when-not m (fail "there is no podman machine"))
    (when (:running? m) (machine-stop!))
    (println "podman machine:" cpus "CPUs," gb "GB")
    (sh "podman" "machine" "set" "--cpus" (str cpus) "--memory" (str (* 1024 gb)))
    (machine-start!)))

(defn ensure-podman! []
  (when-not (podman?) (fail "podman is not installed" (if mac? "(brew install podman)" "")))
  (when-not (out "podman" "info")
    (if (machine?)
      (machine-start!)
      (fail "podman does not answer")))
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

;; ---- the image --------------------------------------------------------------------------------------------

(defn- digest [algo f]
  (let [md (MessageDigest/getInstance algo)]
    (with-open [in (java.io.FileInputStream. (str f))]
      (let [buf (byte-array 1048576)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn sha256 [f] (digest "SHA-256" f))

(defn download!
  "curl URL into FILE unless it is there; resumable."
  [url file]
  (fs/create-dirs (fs/parent file))
  (when-not (fs/exists? file)
    (let [part (str file ".part")]
      (println "downloading" url)
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
          (when-not (= want (digest "MD5" f))
            (fs/delete f)
            (fail "the Arch Linux ARM download is broken (md5); run the build again"))
          (sh "podman" "import" "--arch" "arm64" "--os" "linux" f ref)))
      ref)))

(defn idea-tarball! []
  (let [a (if arm? "-aarch64" "")
        url (str "https://download.jetbrains.com/idea/idea-" idea-version a ".tar.gz")
        f (str cache "/idea-" idea-version a ".tar.gz")]
    (download! url f)
    (let [want (-> (out "curl" "-fsSL" (str url ".sha256")) (str/split #"\s+") first)]
      (when-not (= want (sha256 f))
        (fs/delete f)
        (fail "the IntelliJ download is broken (sha256); run the build again")))
    f))

(defn plugin-zips
  "The plugin zips that go into the image: --plugins DIR or $DEVKIT_PLUGINS when given, else the zips of
  <state>/plugins and <state>/artifacts (a release's output; it wins when both have a file of one name)."
  [args]
  (if-let [d (or (opt args "--plugins" nil) (System/getenv "DEVKIT_PLUGINS"))]
    (vec (sort (map str (fs/glob d "*.zip"))))
    (->> [(str state "/plugins") (str state "/artifacts")]
         (mapcat #(when (fs/directory? %) (fs/glob % "*.zip")))
         (reduce (fn [m z] (assoc m (str (fs/file-name z)) (str z))) (sorted-map))
         vals vec)))

(defn ca-files [] (vec (sort (map str (when (fs/directory? ca-dir) (fs/glob ca-dir "*.pem"))))))

(defn inputs-hash
  "One hash over everything a build bakes in: the image folder, the plugin zips, the CAs. The image
  carries it as a label, so the menu can say when the image is older than its inputs."
  [zips]
  (let [md (MessageDigest/getInstance "SHA-256")
        files (sort (map str (filter fs/regular-file? (fs/glob (str root "/image") "**" {:hidden true}))))]
    (doseq [f files] (.update md (.getBytes (str (fs/relativize root f) "\n"))) (.update md (fs/read-all-bytes f)))
    (doseq [z zips] (.update md (.getBytes (str (fs/file-name z) " " (fs/size z) "\n"))))
    (doseq [c (ca-files)] (.update md (fs/read-all-bytes c)))
    (subs (apply str (map #(format "%02x" %) (.digest md))) 0 16)))

(defn prune-old-images!
  "Remove the images earlier builds left behind (untagged, with devkit's label): each is about 10 GB, and
  the ID-mapped copy podman makes at the first start as much again. One still used by a running VM stays."
  []
  (run 600000 "podman" "image" "prune" "-f" "--filter" "label=devkit.inputs")
  nil)

(defn build!
  "Assemble <state>/ctx (Containerfile, files, plugins, CAs, IDE tarball) and build the image."
  [args]
  (ensure-podman!)
  (let [zips (plugin-zips args)
        _ (when (empty? zips) (fail "no plugin zips: put deliberator-*.zip and inliner-*.zip into" (str state "/plugins")))
        base (base-image!)
        tar (idea-tarball!)]
    (fs/delete-tree ctx)
    (doseq [d [(str ctx "/plugins") (str ctx "/ca") ca-dir]] (fs/create-dirs d))
    (fs/copy (str root "/image/Containerfile") ctx)
    (fs/copy-tree (str root "/image/files") (str ctx "/files"))
    (doseq [z zips] (println "plugin" (str (fs/file-name z))) (fs/unzip z (str ctx "/plugins")))
    (doseq [c (ca-files)] (fs/copy c (str ctx "/ca")))
    (spit (str ctx "/ca/.keep") "")
    (fs/create-link (str ctx "/idea.tar.gz") tar)
    (sh "podman" "build" "--build-arg" (str "BASE=" base)
        "--label" (str "devkit.inputs=" (inputs-hash zips))
        "--label" (str "devkit.plugins=" (str/join " " (map #(str/replace (str (fs/file-name %)) #"\.zip$" "") zips)))
        "-t" image ctx)
    (prune-old-images!)
    (println "the image is built")))

(defn image-info
  "nil when the image is not built, else {:created :size :plugins [..] :stale? (its inputs changed since)}."
  []
  (let [r (run 20000 "podman" "image" "inspect" image "--format" "{{json .}}")]
    (when (zero? (:exit r))
      (let [m (json/parse-string (:out r) true)
            labels (:Labels m)
            built (:devkit.inputs labels)]
        {:created (:Created m) :size (:Size m)
         :plugins (some-> (:devkit.plugins labels) (str/split #" ") vec)
         :stale? (boolean (or (nil? built) (not= built (inputs-hash (plugin-zips []))))) }))))

;; ---- the VM: state, start, stop ---------------------------------------------------------------------------

(defn url [] (str "http://127.0.0.1:" port "/"))

(defn answers?
  "True when the page really answers."
  []
  (try (= 200 (:status (http/get (url) {:timeout 4000 :throw false})))
       (catch Exception _ false)))

(defn socket-path
  "Path of the podman API socket as seen by the machine (or the Linux host)."
  []
  (let [s (str/replace (out "podman" "info" "--format" "{{.Host.RemoteSocket.Path}}") #"^unix://" "")]
    (when (and (not (machine?)) (str/starts-with? s "/run/user"))
      (out "systemctl" "--user" "start" "podman.socket"))
    s))

(defn containers
  "Every container podman knows: [{:name :image :state :status :ports [..] :created :labels}], or
  {:error TEXT} when podman does not answer."
  []
  (let [r (run 20000 "podman" "ps" "-a" "--format" "json")]
    (if-not (zero? (:exit r))
      {:error (or (last (remove str/blank? (str/split-lines (:err r)))) "podman does not answer")}
      (vec (for [c (json/parse-string (:out r) true)]
             {:name (first (:Names c)) :image (:Image c) :state (:State c) :status (:Status c)
              :started (:StartedAt c)
              :ports (vec (distinct (for [p (:Ports c)] (:host_port p))))})))))

(defn container-state
  "nil when there is no VM container, else podman's word (running, exited, ...). :error when podman does not answer."
  []
  (let [cs (containers)]
    (if (map? cs) :error (:state (some #(when (= container (:name %)) %) cs)))))

(defn running? [] (= "running" (container-state)))

(def ^:private inside-script (delay (slurp (str root "/src/devkit/inside.clj"))))

(defn inside
  "What only the VM knows (its units, memory, the repos in /work and in /repos), as a map; nil when it
  does not answer. One `podman exec` of src/devkit/inside.clj."
  []
  (let [r (run 30000 "podman" "exec" "-u" "dev" container "bb" "-e" @inside-script)]
    (when (zero? (:exit r))
      (try (edn/read-string (:out r)) (catch Exception _ nil)))))

(defn mounts
  "What the VM has mounted: [{:to :from :kind}], from podman's record of the container."
  []
  (let [r (run 20000 "podman" "inspect" container "--format" "{{json .Mounts}}")]
    (when (zero? (:exit r))
      (vec (for [m (json/parse-string (:out r) true)]
             {:to (:Destination m) :from (if (= "volume" (:Type m)) (:Name m) (:Source m)) :kind (:Type m)})))))

(defn probe
  "The truth about the VM now: {:state S :words TEXT ...}. S is one of
  :no-podman :no-machine :machine-stopped :not-built :stopped :starting :running :broken."
  []
  (let [m (when (podman?) (machine))]
    (cond
      (not (podman?)) {:state :no-podman :words "podman is not installed"}
      (and mac? (nil? m)) {:state :no-machine :words "there is no podman machine yet"}
      (and m (not (:running? m))) {:state :machine-stopped :words "the podman machine is stopped" :machine m}
      :else
      (let [cs (containers)
            c (when (vector? cs) (some #(when (= container (:name %)) %) cs))]
        (cond
          (map? cs) {:state :no-podman :words (:error cs) :machine m}
          (nil? c) (if (out "podman" "image" "exists" image)
                     {:state :stopped :words "not running" :machine m :containers cs}
                     {:state :not-built :words "the image is not built yet" :machine m :containers cs})
          (not= "running" (:state c))
          {:state :stopped :words (str "stopped (podman says: " (:state c) ")")
           :machine m :containers cs}
          :else
          (let [in (future (inside)) ok (future (answers?))
                in @in ok @ok
                units (:units in)]
            (merge {:machine m :containers cs :inside in :units units :vm c}
                   (cond ok {:state :running :words "the page answers"}
                         (or (nil? in) (every? #{"active" "activating"} (vals units)))
                         {:state :starting :words "starting, the page does not answer yet"}
                         :else {:state :broken :words (str "the page does not answer: " (str/join ", " (for [[u s] units] (str u " " s))))}))))))))

(defn- wait-for-page! []
  (println "waiting for IntelliJ's page")
  (loop [i 0]
    (cond (answers?) nil
          (> i 90) (fail "the page did not answer in 3 minutes; see bb vm:logs")
          :else (do (Thread/sleep 2000) (recur (inc i))))))

(defn up!
  "Start the VM and wait for the page. --work DIR, --repos DIR and --desktop xfce|i3 are remembered in
  the settings. Already running: says so and changes nothing."
  [args]
  (ensure-podman!)
  (let [w (opt args "--work" nil) r (opt args "--repos" nil) d (opt args "--desktop" nil)]
    (when (and d (not (#{"xfce" "i3"} d))) (fail "--desktop is xfce or i3"))
    (when (or w r d)
      (save-settings! (cond-> {} w (assoc :work (str (fs/absolutize w))) r (assoc :repos (str (fs/absolutize r))) d (assoc :desktop d)))))
  (when-not (out "podman" "image" "exists" image) (fail "the image is not built; run bb vm:build"))
  (let [{:keys [state]} (probe)]
    (if (= state :running)
      (println "already running at" (url) "- nothing changed")
      (let [work (str (fs/canonicalize (fs/create-dirs (work-dir))))
            repos (str (fs/canonicalize (fs/create-dirs (repos-dir))))
            sock (socket-path)
            usual "/run/user/1000/podman/podman.sock"]
        (fs/create-dirs ca-dir)
        (when-not (= state :starting)
          (out "podman" "rm" "-f" container)
          (prune-old-images!)
          (println "work " work "(/work in the VM)")
          (println "repos" repos "(/repos in the VM)")
          (println "starting; the first start after a build copies the image and can take minutes")
          (apply run-quiet! "the VM did not start"
                      "podman" "run" "-d" "--name" container "--hostname" "devkit"
              "--systemd=always" "--userns=keep-id:uid=1000,gid=1000" "--user" "root"
              "--security-opt" "label=disable" "--shm-size" "1g"
              "--network" network
              "--tz" (str (java.time.ZoneId/systemDefault))
              "-p" (str "127.0.0.1:" port ":" port)
              "-e" (str "DEVKIT_WORK=" work)
              "-e" (str "DEVKIT_DESKTOP=" (desktop))
              "-e" (str "DEVKIT_PODMAN_SOCK=" sock)
              "-v" (str home-volume ":/home/dev")
              "-v" (str work ":" work)
              "-v" (str repos ":" repos-mount)
              "-v" (str ca-dir ":/ca:ro")
              ;; the host's podman socket: at the usual rootless path, and at its own path on the host
              ;; (on a Mac that is another one), for tools that hand the socket on to a container
              (concat ["-v" (str sock ":" usual)]
                      (when (not= sock usual) ["-v" (str sock ":" sock)])
                      [image])))
        (wait-for-page!)
        (println "IntelliJ is at" (url))))))

(defn down!
  "Stop and remove the container (the home volume and the work folder stay). Says so when there is none."
  []
  (let [m (machine)]
    (if (or (and m (not (:running? m))) (nil? (container-state)))
      (println "already stopped - nothing to do")
      (do (println "stopping the VM (IntelliJ saves its state)")
          (run 60000 "podman" "stop" "-t" "30" container)
          (run 30000 "podman" "rm" "-f" container)
          (println "stopped (your home and work folder are kept)")))))

(defn- need-running! [] (when-not (running?) (fail "the VM is not running: start it first")))

(defn- in-work
  "A bash script that goes to the work folder (to SUB in it) by its real path, then runs SCRIPT. The real
  path, not the /work link: a tool started there hands paths to the host's podman."
  [sub script]
  (str "cd \"${DEVKIT_WORK:-/work}" (when sub (str "/" sub)) "\" && " script))

(defn vm-sh
  "bash -lc SCRIPT in the VM as dev, in the work folder (or SUB of it): {:exit :out :err}."
  ([script] (vm-sh nil script))
  ([sub script] (run 300000 "podman" "exec" "-u" "dev" container "bash" "-lc" (in-work sub script))))

(defn vm-sh!
  "The same with its output shown; throws when it fails."
  ([script] (vm-sh! nil script))
  ([sub script] (sh "podman" "exec" "-u" "dev" container "bash" "-lc" (in-work sub script))))

(defn shell!
  "A login shell as dev in the VM, in the work folder (or SUB of it)."
  ([] (shell! nil))
  ([sub]
   (need-running!)
   (tty "podman" "exec" "-it" "-u" "dev" container "bash" "-lc" (in-work sub "exec bash -l"))
   nil))

(defn logs! [args]
  (need-running!)
  (apply tty "podman" "exec" "-it" container "journalctl" "-u" "idea" "-u" "xpra" "-u" "devkit-ca" "-u" "devkit-boot" "-n" "200" "--no-pager"
         (when (some #{"-f"} args) ["-f"]))
  nil)

(defn open!
  "The IDE's page in a browser. On a Mac with Chrome: an app window (no tabs, no address bar, so the page
  has the whole window and fewer keys go to the browser)."
  []
  (let [chrome "/Applications/Google Chrome.app"]
    (cond (and mac? (fs/exists? chrome)) (p/process "open" "-na" "Google Chrome" "--args" (str "--app=" (url)))
          mac? (p/process "open" (url))
          :else (p/process {:out :discard :err :discard} "xdg-open" (url)))
    (println "opened" (url))))

(defn desktop!
  "Choose the desktop on the display: xfce (no window-manager keys, every key reaches IntelliJ) or i3
  (tiling, takes Alt+digits). Remembered; a running VM switches at once and the IDE stays open."
  [d]
  (when-not (#{"xfce" "i3"} d) (fail "the desktop is xfce or i3"))
  (save-settings! {:desktop d})
  (if (running?)
    (let [r (run 30000 "podman" "exec" "-u" "dev" container "sh" "-c"
                 (str "echo " d " > /run/devkit/desktop; pkill -x xfwm4; pkill -x i3; true"))]
      (when-not (zero? (:exit r)) (fail "the VM did not switch:" (str/trim (:err r))))
      (println "the desktop is" d "now"))
    (println "the desktop will be" d "at the next start")))

(defn status!
  "The true state, in words; the URL only when the page answers."
  []
  (let [{:keys [state words units inside]} (probe)]
    (println (str/upper-case (name state)) "-" words)
    (when (#{:running :starting :broken} state)
      (when (= state :running) (println "url:    " (url)))
      (println "units:  " (str/join ", " (for [[u s] units] (str u " " s))))
      (when-let [m (:memory inside)] (println "memory: " (format "%.1f GB" (/ (:used m) 1073741824.0))))
      (println "desktop:" (:desktop inside))
      (doseq [{:keys [to from]} (sort-by :to (mounts))] (println (format "mount:   %-36s %s" to from)))
      (doseq [r (:work inside)] (println "repo:   " (:name r) (:branch r))))
    (case state
      :no-machine (println "make one: bb vm (the menu), or podman machine init")
      :machine-stopped (println "start it: bb vm (the menu), or podman machine start")
      :not-built (println "build it: bb vm:build")
      :stopped (println "start it: bb vm:up")
      nil)))

;; ---- repositories -----------------------------------------------------------------------------------------

(defn- repo-name! [n]
  (when-not (and (string? n) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" n)) (fail "not a repository name:" (pr-str n)))
  n)

(defn import!
  "Working copy /work/NAME from the bare repository /repos/NAME.git (its origin: a push from the VM lands
  in the host's bare repository). Already there: says so."
  [n]
  (repo-name! n)
  (need-running!)
  (when-not (fs/directory? (str (repos-dir) "/" n ".git")) (fail "no repository" (str n ".git") "in" (repos-dir)))
  (vm-sh! (str "if [ -e '" n "/.git' ]; then echo 'already in the VM: /work/" n "'; "
               "else git clone -q /repos/" n ".git " n " && echo 'imported " n " into /work/" n "'; fi")))

(def checkout! import!)

(defn remove-repo!
  "Delete the working copy /work/NAME (the bare repository on the host stays)."
  [n]
  (repo-name! n)
  (need-running!)
  (vm-sh! (str "test -e '" n "/.git' && rm -rf -- '" n "' && echo 'removed /work/" n " (the repository on the host is untouched)'")))

(defn git!
  "git ARGS in /work/NAME, output shown."
  [n & args]
  (repo-name! n)
  (need-running!)
  (apply sh "podman" "exec" "-u" "dev" container "bash" "-lc" (in-work n "git \"$@\"") "git" args))

(defn switch!
  "Switch /work/NAME to BRANCH; the origin is fetched first, so a branch made on the host a moment ago is there."
  [n branch]
  (repo-name! n)
  (need-running!)
  (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" (str branch)) (fail "not a branch name:" (pr-str branch)))
  (sh "podman" "exec" "-u" "dev" container "bash" "-lc"
      (in-work n "git fetch -q origin && git switch \"$1\" 2>&1 && echo \"$0 is on $1\"") n branch))

(defn open-in-ide!
  "Open /work/NAME as a project in the running IntelliJ."
  [n]
  (repo-name! n)
  (need-running!)
  (let [r (run 30000 "podman" "exec" "-d" "-u" "dev" "-e" "DISPLAY=:100" "-e" "HOME=/home/dev"
               container "bash" "-lc" (in-work n "exec /opt/idea/bin/idea.sh \"$PWD\""))]
    (when-not (zero? (:exit r)) (fail "IntelliJ did not take it:" (str/trim (:err r))))
    (println "IntelliJ opens" n)))

(defn hidden?
  "A repository the settings hide (:hide, a regex on the name)."
  [n]
  (boolean (some-> (:hide (settings)) re-pattern (re-find n))))

(defn host-repos
  "Names of the bare repositories in the repos folder, read on the host (works with the VM stopped)."
  []
  (let [d (repos-dir)]
    (->> (when (fs/directory? d) (fs/list-dir d))
         (filter #(and (fs/directory? %) (str/ends-with? (str %) ".git")))
         (map #(str/replace (str (fs/file-name %)) #"\.git$" ""))
         (remove hidden?) sort vec)))

;; ---- containers next to the VM ----------------------------------------------------------------------------

(defn sibling-kind
  "What a container next to the VM is, by its name and image: :aws (Floci), :lambda (a function Floci
  started), :database, else :service."
  [{:keys [name image]}]
  (cond (re-find #"(?i)floci-aws-|lambda" name) :lambda
        (re-find #"(?i)floci|localstack" (str name " " image)) :aws
        (re-find #"(?i)postgres|mariadb|mysql|redis|mongo" (str name " " image)) :database
        :else :service))

(defn siblings
  "The containers next to the VM that belong to what is developed in it: named in the settings (:siblings,
  a regex), else Floci and whatever Deliberator started."
  [cs]
  (let [re (re-pattern (or (:siblings (settings)) "(?i)floci|^deliberator-"))]
    (->> cs
         (remove #(= container (:name %)))
         (filter #(re-find re (:name %)))
         (map #(assoc % :kind (sibling-kind %)
                      :short (str/replace (:name %) #"^deliberator-[^-]+-(service-)?" "")))
         (sort-by (juxt #(.indexOf [:aws :database :service :lambda] (:kind %)) #(if (= "running" (:state %)) 0 1) :short))
         vec)))

(defn container!
  "podman start|stop|restart NAME, with its words."
  [verb n]
  (when-not (#{"start" "stop" "restart"} verb) (fail "start, stop or restart"))
  (sh "podman" verb n)
  (println n (case verb "start" "started" "stop" "stopped" "restart" "restarted")))

;; ---- Floci: the AWS services on this machine --------------------------------------------------------------

(def ^:dynamic *aws-endpoint* "where the local AWS answers; `aws` binds it to the port Floci publishes" "http://127.0.0.1:4566")

(defn- aws-call
  "One unsigned call to the local AWS (Floci checks no signature; the credential scope names the service
  and the region). kind :json (X-Amz-Target, BODY a map), :query (Action=), :get (a REST path). Parsed
  JSON, or the XML text; nil when it fails."
  [region {:keys [svc kind target version path action]} & [body]]
  (try
    (let [auth (str "AWS4-HMAC-SHA256 Credential=test/20260101/" region "/" svc "/aws4_request, SignedHeaders=host, Signature=0")
          base {:timeout 3000 :throw false}
          r (case kind
              :json (http/post *aws-endpoint* (assoc base :headers {"Authorization" auth "X-Amz-Target" target
                                                                  "Content-Type" (str "application/x-amz-json-" (or version "1.1"))}
                                                   :body (json/generate-string (or body {}))))
              :query (http/post *aws-endpoint* (assoc base :headers {"Authorization" auth "Content-Type" "application/x-www-form-urlencoded"}
                                                    :body (str "Action=" action "&Version=" version)))
              :get (http/get (str *aws-endpoint* path) (assoc base :headers {"Authorization" auth})))]
      (when (= 200 (:status r))
        (if (str/starts-with? (str/triml (:body r)) "<") (:body r) (json/parse-string (:body r) true))))
    (catch Exception _ nil)))

(defn- xml-all [tag s] (map second (re-seq (re-pattern (str "<" tag ">([^<]*)</" tag ">")) (str s))))
(defn- tail-name [s] (last (str/split (str s) #"[/:]")))
(defn- named [names] (for [n names] {:name n}))

(def aws-lists
  "What is listed, per service: how to ask, and :items, which reads [{:name :detail}] out of the answer
  (it gets the region too, for a second question about each: a queue's depth, a table's size)."
  [{:label "lambda" :svc "lambda" :kind :get :path "/2015-03-31/functions"
    :items (fn [r _] (for [f (:Functions r)]
                       {:name (:FunctionName f)
                        :detail (str/join " · " (remove nil? [(:Runtime f) (:Handler f) (some-> (:MemorySize f) (str " MB")) (some-> (:Timeout f) (str " s timeout"))]))}))}
   {:label "api gateway" :svc "apigateway" :kind :get :path "/v2/apis"
    :items (fn [r _] (for [a (:Items r)] {:name (:Name a) :detail (str/join " · " (remove nil? [(:ProtocolType a) (:ApiId a)]))}))}
   {:label "sqs" :svc "sqs" :kind :json :target "AmazonSQS.ListQueues" :version "1.0"
    :items (fn [r region]
             (for [u (:QueueUrls r)
                   :let [a (:Attributes (aws-call region {:svc "sqs" :kind :json :target "AmazonSQS.GetQueueAttributes" :version "1.0"}
                                                  {:QueueUrl u :AttributeNames ["All"]}))]]
               {:name (tail-name u)
                :detail (when a (str (:ApproximateNumberOfMessages a) " waiting · " (:ApproximateNumberOfMessagesNotVisible a) " in flight"))}))}
   {:label "sns" :svc "sns" :kind :query :action "ListTopics" :version "2010-03-31" :items (fn [r _] (named (map tail-name (xml-all "TopicArn" r))))}
   ;; every region has a bus called default: it says nothing
   {:label "eventbridge" :svc "events" :kind :json :target "AWSEvents.ListEventBuses" :items (fn [r _] (named (remove #{"default"} (map :Name (:EventBuses r)))))}
   {:label "scheduler" :svc "scheduler" :kind :get :path "/schedules" :items (fn [r _] (for [x (:Schedules r)] {:name (:Name x) :detail (:State x)}))}
   {:label "dynamodb" :svc "dynamodb" :kind :json :target "DynamoDB_20120810.ListTables" :version "1.0"
    :items (fn [r region]
             (for [t (:TableNames r)
                   :let [d (:Table (aws-call region {:svc "dynamodb" :kind :json :target "DynamoDB_20120810.DescribeTable" :version "1.0"} {:TableName t}))]]
               {:name t :detail (when d (str (:ItemCount d) " items"))}))}
   {:label "s3" :svc "s3" :kind :get :path "/" :items (fn [r _] (named (xml-all "Name" r)))}
   {:label "ssm" :svc "ssm" :kind :json :target "AmazonSSM.DescribeParameters" :items (fn [r _] (for [x (:Parameters r)] {:name (:Name x) :detail (:Type x)}))}
   {:label "secrets" :svc "secretsmanager" :kind :json :target "secretsmanager.ListSecrets" :items (fn [r _] (named (map :Name (:SecretList r))))}
   {:label "ecs" :svc "ecs" :kind :json :target "AmazonEC2ContainerServiceV20141113.ListClusters" :items (fn [r _] (named (map tail-name (:clusterArns r))))}
   {:label "logs" :svc "logs" :kind :json :target "Logs_20140328.DescribeLogGroups" :items (fn [r _] (named (map :logGroupName (:logGroups r))))}])

(defn aws-regions
  "The regions looked at: :aws-regions of the settings, else AWS_REGION / AWS_DEFAULT_REGION and the usual ones."
  []
  (or (:aws-regions (settings))
      (vec (distinct (remove nil? [(System/getenv "AWS_REGION") (System/getenv "AWS_DEFAULT_REGION")
                                   "eu-central-1" "us-east-1" "eu-west-1"])))))

(defn aws
  "The local AWS (Floci) on host port PORT, or nil when it does not answer:
  {:version :port :resources [{:label :region :items [{:name :detail}]}]} - only what exists. REGIONS:
  where to look (default: aws-regions). Every call shows in Floci's own log, so the menu asks rarely
  and, once it knows where the resources are, only there."
  [port & [regions]]
  (binding [*aws-endpoint* (str "http://127.0.0.1:" port)]
    (let [h (try (let [r (http/get (str *aws-endpoint* "/_floci/health") {:timeout 1500 :throw false})]
                   (when (= 200 (:status r)) (json/parse-string (:body r) true)))
                 (catch Exception _ nil))
          endpoint *aws-endpoint*]
      (when h
        {:version (:version h)
         :port port
         :resources (->> (for [region (or (seq regions) (aws-regions)) l aws-lists] [region l])
                         (pmap (fn [[region l]]
                                 (binding [*aws-endpoint* endpoint]
                                   (let [items (try (->> ((:items l) (aws-call region l) region)
                                                         (remove #(str/blank? (:name %))) (sort-by :name) vec)
                                                    (catch Exception _ []))]
                                     (when (seq items) {:label (:label l) :region region :items items})))))
                         (remove nil?)
                         ;; s3 answers the same in every region: keep the first
                         (reduce (fn [acc r] (if (some #(and (= (:label %) (:label r)) (= (map :name (:items %)) (map :name (:items r)))) acc) acc (conj acc r))) [])
                         vec)}))))

;; ---- corporate CAs ----------------------------------------------------------------------------------------

(defn- pem-files
  "The PEM/CRT files named by the arguments: a file, or every .pem/.crt/.cer in a folder."
  [paths]
  (vec (distinct (mapcat (fn [p] (cond (fs/directory? p) (filter #(re-find #"(?i)\.(pem|crt|cer)$" (str %)) (fs/list-dir p))
                                       (fs/exists? p) [(fs/path p)]
                                       :else (fail "no such file:" p)))
                         paths))))

(defn ca-install!
  "Install corporate root CAs given as PEM files (or folders of them). Only the paths given are read;
  the macOS keychain is never touched. Each is kept in <state>/ca (mounted at /ca, baked into the next
  build), put in the podman machine's trust store so image pulls work, and installed in a running VM by
  devkit-ca (system store, and so every Java store). Files already installed unchanged are skipped."
  [args]
  (let [files (pem-files (map #(str (fs/expand-home %)) args))]
    (when (empty? files) (fail "usage: bb ca:install FILE.pem|DIR ..."))
    (ensure-podman!)
    (fs/create-dirs ca-dir)
    (let [changed (doall
                    (for [f files
                          :let [txt (slurp (str f))
                                base (str/replace (str (fs/file-name f)) #"(?i)\.(pem|crt|cer)$" "")
                                dest (str ca-dir "/" base ".pem")]
                          :when (do (when-not (str/includes? txt "-----BEGIN CERTIFICATE-----")
                                      (fail (str f) "is not a PEM certificate"))
                                    (not (and (fs/exists? dest) (= txt (slurp dest)))))]
                      (do (spit dest txt) {:name (str base ".pem") :dest dest})))]
      (if (empty? changed)
        (println "nothing new: all" (count files) "file(s) already installed")
        (do (doseq [{:keys [name dest]} changed]
              (println "kept" dest)
              (when (machine?)
                (-> (p/process {:in (fs/file dest) :err :string :out :string}
                               "podman" "machine" "ssh" (str "sudo tee /etc/pki/ca-trust/source/anchors/" name " >/dev/null"))
                    p/check)))
            (if (machine?)
              (do (sh "podman" "machine" "ssh" "sudo update-ca-trust; systemctl --user stop podman.service podman.socket; systemctl --user start podman.socket; true")
                  (println "the podman machine trusts them"))
              (println "no podman machine: this host's own trust store is used for pulls; add the CAs there if pulls fail"))
            (when (running?)
              (sh "podman" "exec" container "systemctl" "restart" "devkit-ca")
              (println "the running VM trusts them"))
            (println "build the image again so they are part of it"))))))

;; ---- everything the menu shows ----------------------------------------------------------------------------

(defn snapshot
  "The whole picture, read at once (the slow parts side by side):
  the probe's map plus :image, :mounts, :siblings, :aws, :host-repos, :settings, :cas, :zips.
  aws-of: a function of the port Floci publishes that answers its inventory (default: ask Floci now);
  the menu passes one that asks only now and then."
  [& [aws-of]]
  (let [pr (future (probe))
        img (future (when (podman?) (image-info)))
        p @pr
        up? (#{:running :starting :broken} (:state p))
        mnt (future (when up? (mounts)))
        sibs (siblings (or (:containers p) []))
        aws-port (or (some #(when (and (= :aws (:kind %)) (= "running" (:state %))) (first (:ports %))) sibs) 4566)
        aws* (future ((or aws-of aws) aws-port))]
    (assoc p
           :image @img
           :mounts @mnt
           :siblings sibs
           :others (count (remove #(= container (:name %)) (or (:containers p) [])))
           :aws @aws*
           :host-repos (host-repos)
           :settings {:work (work-dir) :repos (repos-dir) :desktop (desktop)}
           :cas (mapv #(str (fs/file-name %)) (ca-files))
           :zips (mapv #(str (fs/file-name %)) (plugin-zips [])))))
