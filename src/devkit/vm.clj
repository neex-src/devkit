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
(def default-work (str root "/.scratch/work"))

(def container "devkit")
(def image "localhost/devkit:latest")
(def network "devkit")
(def home-volume "devkit-home")
(def port 14500)
(def idea-version "2026.3")

(def arm? (#{"aarch64" "arm64"} (System/getProperty "os.arch")))

(defn sh
  "Run a command, inherit output, throw on failure."
  [& args] (-> (apply p/process {:inherit true} args) p/check) nil)

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
(defn ensure-podman! []
  (when-not (out "podman" "info")
    (if (machine?)
      (do (println "starting podman machine") (sh "podman" "machine" "start"))
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
  (let [s (out "podman" "info" "--format" "{{.Host.RemoteSocket.Path}}")]
    (when (and (not (machine?)) (str/starts-with? s "/run/user"))
      (out "systemctl" "--user" "start" "podman.socket"))
    s))

(defn running? [] (= "true" (out "podman" "inspect" "-f" "{{.State.Running}}" container)))

(defn url [] (str "http://127.0.0.1:" port "/"))

(defn up!
  "Start the VM (home volume, work folder, CA folder, host podman socket) and wait for the GUI."
  [args]
  (ensure-podman!)
  (when-not (out "podman" "image" "exists" image) (throw (ex-info "no image; run bb vm:build" {})))
  (let [work (str (fs/canonicalize (fs/create-dirs (opt args "--work" default-work))))]
    (fs/create-dirs ca-dir)
    (when-not (running?)
      (out "podman" "rm" "-f" container)
      (sh "podman" "run" "-d" "--name" container "--hostname" "devkit"
          "--systemd=always" "--userns=keep-id:uid=1000,gid=1000" "--user" "root"
          "--security-opt" "label=disable" "--shm-size" "1g" "--network" "pasta:-T,4566,-T,5432,-T,8000-8999"
          "-p" (str "127.0.0.1:" port ":" port)
          "-v" (str home-volume ":/home/dev")
          "-v" (str work ":/work")
          "-v" (str ca-dir ":/ca:ro")
          "-v" (str (socket-path) ":/run/user/1000/podman/podman.sock")
          image))
    (println "waiting for the GUI")
    (loop [i 0]
      (cond (some-> (out "curl" "-fsS" "-m" "3" "-o" "/dev/null" "-w" "%{http_code}" (url)) (= "200")) nil
            (> i 60) (throw (ex-info "GUI did not come up; see bb vm:logs" {}))
            :else (do (Thread/sleep 2000) (recur (inc i)))))
    (println "open" (url))))

(defn down! []
  (ensure-podman!)
  (out "podman" "stop" "-t" "30" container)
  (out "podman" "rm" "-f" container)
  (println "down (volume" home-volume "kept)"))

(defn status! []
  (ensure-podman!)
  (if (running?)
    (do (println "RUNNING" (url))
        (println (out "podman" "exec" container "systemctl" "is-active" "xpra" "idea" "devkit-ca"))
        (println "memory:" (out "podman" "stats" "--no-stream" "--format" "{{.MemUsage}}" container))
        (println "work:" (out "podman" "inspect" "-f" "{{range .Mounts}}{{if eq .Destination \"/work\"}}{{.Source}}{{end}}{{end}}" container) "-> /work in the VM"))
    (println "STOPPED")))

(defn shell! []
  (sh "podman" "exec" "-it" "-u" "dev" "-w" "/work" container "bash" "-l"))

(defn logs! [args]
  (apply sh "podman" "exec" container "journalctl" "-u" "idea" "-u" "xpra" "-u" "devkit-ca" "-n" "200" "--no-pager"
         (when (some #{"-f"} args) ["-f"])))

(defn ca-install!
  "Install a corporate root CA (PEM): keep it in .scratch/ca (mounted at /ca and baked
  into the next build), put it in the podman machine's trust store so image pulls work,
  and re-run devkit-ca in a running VM."
  [args]
  (let [f (first args)]
    (when-not (and f (fs/exists? f)) (throw (ex-info "usage: bb ca:install FILE.pem" {})))
    (ensure-podman!)
    (let [name (str (fs/file-name f))
          name (if (str/ends-with? name ".pem") name (str name ".pem"))]
      (fs/create-dirs ca-dir)
      (fs/copy f (str ca-dir "/" name) {:replace-existing true})
      (println "kept as" (str ca-dir "/" name))
      (if (machine?)
        (do (-> (p/process {:in (fs/file f) :inherit :err :out :inherit}
                           "podman" "machine" "ssh" (str "sudo tee /etc/pki/ca-trust/source/anchors/" name " >/dev/null"))
                p/check)
            (sh "podman" "machine" "ssh" "sudo update-ca-trust; systemctl --user stop podman.service podman.socket; systemctl --user start podman.socket; true")
            (println "podman machine trusts it"))
        (println "no podman machine: this host's own trust store is used for pulls; add the CA there if pulls fail"))
      (when (running?)
        (sh "podman" "exec" container "systemctl" "restart" "devkit-ca")
        (println "running VM updated")))))
