;; Runs INSIDE the VM: `podman exec -u dev devkit bb -e <this file>` (devkit.vm/inside). Not a namespace
;; of the host program. Prints one EDN map of what only the VM knows:
;;   :units   {"xpra" "active" "idea" "active"}
;;   :memory  {:used BYTES :limit BYTES-or-nil}      :uptime SECONDS       :desktop "xfce"|"i3"
;;   :work    the working copies in /work   [{:name :branch :dirty :ahead :behind :last :status [..] :log [..] :branches [..]}]
;;   :repos   the bare repositories in /repos [{:name :branch :last :at :branches N :log [..]}]
;;   :workspaces  Deliberator's workspaces that have an IDE project [{:name :project PATH :repos [..]}]
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(defn sh [dir & cmd]
  (let [r (apply p/sh {:dir (str dir)} cmd)]
    (when (zero? (:exit r)) (str/trimr (:out r)))))

(defn lines [s] (if (str/blank? s) [] (str/split-lines s)))

(def log-format "--format=%C(yellow)%h%C(reset) %s %C(dim)%cr%C(reset)")

(defn work-repo [d]
  (let [g (fn [& a] (apply sh d "git" a))
        st (lines (g "status" "--porcelain"))
        ab (some-> (g "rev-list" "--left-right" "--count" "@{u}...HEAD") (str/split #"\s+"))]
    {:name (str (fs/file-name d))
     :branch (or (g "symbolic-ref" "--short" "-q" "HEAD") (some->> (g "rev-parse" "--short" "HEAD") (str "detached ")))
     :dirty (count st)
     :behind (or (some-> ab first parse-long) 0)
     :ahead (or (some-> ab second parse-long) 0)
     :upstream? (some? ab)
     :last (g "log" "-1" "--format=%cr")
     :status (vec (take 15 st))
     :log (lines (g "log" "--color=always" log-format "-12"))
     :branches (lines (g "branch" "--format=%(refname:short)"))
     ;; what the origin has NOW (a bare repo of the host, a folder here): the clone's own origin/* refs are as old as its
     ;; last fetch, and a branch made on the host since would be missing. Elsewhere: those refs (origin/HEAD shows as
     ;; "origin" in the short form, so only names with a branch part).
     :remote-branches (let [origin (g "remote" "get-url" "origin")]
                        (if (and origin (fs/directory? origin))
                          (mapv #(str "origin/" %) (lines (sh origin "git" "branch" "--format=%(refname:short)")))
                          (vec (filter #(and (str/includes? % "/") (not (str/ends-with? % "/HEAD")))
                                       (lines (g "branch" "-r" "--format=%(refname:short)"))))))}))

(defn bare-repo [d]
  (let [g (fn [& a] (apply sh d "git" a))]
    {:name (str/replace (str (fs/file-name d)) #"\.git$" "")
     :branch (g "symbolic-ref" "--short" "-q" "HEAD")
     :last (g "log" "-1" "--format=%cr")
     :at (or (some-> (g "log" "-1" "--format=%ct") parse-long) 0)
     :branches (count (lines (g "branch" "--format=%(refname:short)")))
     :log (lines (g "log" "--color=always" log-format "-12"))}))

(defn dirs [root pred]
  (->> (when (fs/directory? root) (fs/list-dir root))
       (filter #(and (fs/directory? %) (pred %)))
       (sort-by #(str (fs/file-name %)))))

(defn number-in [f]
  (try (parse-long (str/trim (slurp f))) (catch Exception _ nil)))

;; systemctl as dev has no bus to ask (the container runs no D-Bus system bus); root has systemd's own socket
(let [units (zipmap ["xpra" "idea"] (str/split-lines (:out (p/sh "sudo" "-n" "systemctl" "is-active" "xpra" "idea"))))
      desktop (let [f "/run/devkit/desktop"]
                (if (fs/exists? f)
                  (str/trim (slurp f))
                  (or (second (re-find #"DEVKIT_DESKTOP=(\S+)" (try (slurp "/run/devkit.env") (catch Exception _ "")))) "xfce")))]
  (prn {:units units
        :memory {:used (number-in "/sys/fs/cgroup/memory.current") :limit (number-in "/sys/fs/cgroup/memory.max")}
        :uptime (some-> (sh "/" "ps" "-o" "etimes=" "-p" "1") str/trim parse-long)
        :desktop desktop
        :workspaces (let [home (or (second (re-find #"(?m)^DELIBERATOR_HOME=(.+)$" (try (slurp "/run/devkit.env") (catch Exception _ ""))))
                                   "~/.deliberator")]
                      (vec (for [d (dirs (str home "/workspaces") #(fs/directory? (fs/path % "project")))
                                 :let [ws (try (read-string (slurp (str (fs/path d "workspace.edn")))) (catch Exception _ nil))]]
                             {:name (str (fs/file-name d)) :project (str (fs/path d "project"))
                              :repos (vec (sort (keep :name (:repositories ws))))})))
        :work (vec (pmap work-repo (dirs "/work" #(fs/exists? (fs/path % ".git")))))
        :repos (vec (pmap bare-repo (dirs "/repos" #(str/ends-with? (str %) ".git"))))}))
