(ns devkit.tui
  "`bb vm`: the menu, on fzf.

  Two tabs. `vm` is what you look at: the VM in one line (enter starts or stops it), IntelliJ (enter opens
  it), what is mounted, the containers running next to the VM (Floci and its AWS resources, databases,
  services), the repos in the VM and the ones that can be imported. `setup` holds what is done once or
  rarely: the podman machine, the image, plugins, company CAs, the desktop, the folders, a shell, logs.

  One next step: when something is missing (podman, the podman machine, the image) it is the first row
  and everything else is grey. A one-time thing that is not done shows as a warning on top of `vm` and is
  gone once it is done.

  The right pane shows the row under the cursor; its title says what enter does there. A row with several
  actions opens a short list of them. Tab changes the tab, typing filters, esc leaves.

  How it works: a snapshot (devkit.vm/snapshot) is turned into rows; rows, previews and titles are files
  in <state>/tui, fzf only cats them. A thread takes a new snapshot every few seconds and tells the
  showing fzf to reload (its --listen port); the cursor stays on its row. Work that takes time runs on a
  progress screen (the title, the time, the last lines) and is kept in <state>/logs."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]
            [devkit.vm :as vm]))

;; ---- words and colours ------------------------------------------------------------------------------------

(defn- c [code s] (str "\033[" code "m" s "\033[0m"))
(def ^:private green (partial c "32"))
(def ^:private yellow (partial c "33"))
(def ^:private red (partial c "31"))
(def ^:private cyan (partial c "36"))
(def ^:private dim (partial c "2"))
(def ^:private bold (partial c "1"))

(defn- plain [s] (str/replace (str s) #"\033\[[0-9;]*m" ""))

(defn- pad
  "s on n columns (colour codes do not count)."
  [s n]
  (let [w (count (plain s))] (if (< w n) (str s (apply str (repeat (- n w) " "))) s)))

(defn- cut [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- home-rel [path] (some-> path (str/replace (re-pattern (str "^" (java.util.regex.Pattern/quote (System/getProperty "user.home")))) "~")))

(defn- gb [bytes] (when bytes (format "%.1f GB" (/ bytes 1073741824.0))))

(defn- dur
  "Seconds in words: 45 s, 12 min, 2 h 5 min, 3 days."
  [s]
  (cond (nil? s) ""
        (< s 60) (str s " s")
        (< s 3600) (str (quot s 60) " min")
        (< s 86400) (let [m (quot (mod s 3600) 60)] (str (quot s 3600) " h" (when (pos? m) (str " " m " min"))))
        :else (str (quot s 86400) " days")))

(defn- since
  "An ISO time as how long ago."
  [iso]
  (try (str (dur (.getSeconds (java.time.Duration/between (java.time.Instant/parse iso) (java.time.Instant/now)))) " ago")
       (catch Exception _ nil)))

(defn- plural [n word] (str n " " word (when (not= 1 n) (if (str/ends-with? word "ch") "es" "s"))))

(defn- term-size
  "[rows cols] of the terminal."
  []
  (try (let [[r c] (-> (p/shell {:out :string :err :string :continue true} "sh" "-c" "stty size </dev/tty") :out str/trim (str/split #"\s+"))]
         [(parse-long r) (parse-long c)])
       (catch Exception _ [40 120])))

;; ---- rows -------------------------------------------------------------------------------------------------
;; A row: {:id :text :preview TEXT :hint (what enter does, the right pane's title) :act}. A header row:
;; {:id :header}. :act is nil or one of
;;   {:run f :title T}              f on the progress screen     (+ :confirm QUESTION, :yes-first true)
;;   {:quiet f}                     f at once, its last line shown on top
;;   {:tty f}                       f on the terminal (a shell, a pager)
;;   {:menu [[label act]..]}        a short list of actions
;;   {:ask PROMPT :default D :then (fn [answer] act)}
;;   {:view NAME}                   another list (the AWS resources)

(def ^:dynamic *w* "width of the name column" 14)
(def ^:dynamic *cols* "columns of the list" 60)

(defn- row [id text & {:as more}] (merge {:id id :text text} more))

(defn- hdr [id title]
  {:id (str "h." id)
   :header (c "1;34" (str "── " title " " (apply str (repeat (max 3 (- *cols* (count title) 6)) "─"))))})

(defn- line
  "A row's text: a mark, the name, a state word, details."
  [mark nm state details]
  (str (or mark " ") " " (bold (pad (cut nm *w*) *w*)) "  " (pad (or state "") 10) " " (dim (or details ""))))

(defn- line2
  "A row's text without a state word: the name, then details."
  [nm details]
  (str "  " (bold (pad (cut nm *w*) *w*)) "  " (dim (or details ""))))

(defn- kv [k v] (str (dim (pad k 11)) v))

(defn- bar
  "used of total as a ten-step bar."
  [used total]
  (let [n (max 0 (min 10 (Math/round (* 10.0 (/ (double used) (double total))))))]
    (str ((cond (> n 8) red (> n 6) yellow :else green) (apply str (repeat n "█"))) (dim (apply str (repeat (- 10 n) "░"))))))

(defn- up? [s] (#{:running :starting :broken} (:state s)))

(declare restart-offer)

;; -- what needs doing first

(defn- blocker
  "The one thing in the way, as a row, or nil."
  [s]
  (case (:state s)
    :no-podman (if (vm/podman?)
                 (row "block" (str (red "✗") " " (bold "podman does not answer"))
                      :hint "podman"
                      :preview (str (:words s) "\n\nIs the podman service running? Try: podman info"))
                 (row "block" (str (red "✗") " " (bold "podman is not installed"))
                      :hint "podman"
                      :preview (str "The VM is a podman container.\n\n"
                                    (if vm/mac? "  brew install podman\n\nthen come back here: the menu makes the podman machine." "Install podman with your package manager."))))
    :no-machine (row "block" (str (yellow "▶") " " (bold "make the podman machine"))
                     :hint "⏎ make it"
                     :preview "On a Mac podman runs its containers in a small Linux machine.\nThere is none yet.\n\n6 CPUs, 12 GB memory, 80 GB disk. A download, a few minutes, once."
                     :act {:run vm/machine-init! :title "making the podman machine"})
    :machine-stopped (row "block" (str (yellow "▶") " " (bold "start the podman machine"))
                          :hint "⏎ start it"
                          :preview (let [m (:machine s)]
                                     (str "The podman machine is stopped, so nothing can run.\n\n"
                                          (kv "machine" (:name m)) "\n" (kv "size" (str (:cpus m) " CPUs · " (gb (:memory m)) " · " (gb (:disk m)) " disk"))
                                          "\n\nAbout a minute."))
                          :act {:run vm/machine-start! :title "starting the podman machine"})
    :not-built (if (empty? (:zips s))
                 (row "block" (str (red "✗") " " (bold "no plugin zips to build with"))
                      :hint "plugins"
                      :preview (str "The image is built with the IDE plugins inside.\nPut the zips here, then come back:\n\n  "
                                    (home-rel vm/state) "/plugins/\n\n  deliberator-<version>.zip\n  inliner-<version>.zip"))
                 (row "block" (str (yellow "▶") " " (bold "build the image"))
                      :hint "⏎ build it"
                      :preview (str "The VM's image is not built yet.\n\nArch Linux, IntelliJ IDEA " vm/idea-version ", the desktop, JDKs, git, terraform, podman.\n"
                                    "About 10 minutes and 13 GB, once.\n\n" (kv "plugins" (str/join "  " (:zips s)))
                                    "\n" (kv "CAs" (if (seq (:cas s)) (str/join "  " (:cas s)) "none")))
                      :act {:run #(vm/build! []) :title "building the image"}))
    nil))

(defn- grey [why r]
  (if (:header r) r (assoc r :text (dim (plain (:text r))) :act nil :hint why)))

;; -- the vm tab

(defn- vm-preview [s]
  (let [in (:inside s) m (:memory in) limit (or (:limit m) (get-in s [:machine :memory]))]
    (str/join "\n"
              (remove nil?
                      [(kv "state" (case (:state s) :running (green "running") :starting (yellow "starting") :broken (red "broken") (:words s)))
                       (when (= :running (:state s)) (kv "page" (vm/url)))
                       (when (:uptime in) (kv "up" (dur (:uptime in))))
                       (when (:used m) (kv "memory" (str (gb (:used m)) (when limit (str " of " (gb limit) "  " (bar (:used m) limit))))))
                       (when in (kv "desktop" (:desktop in)))
                       (when (:units s) (kv "inside" (str/join "   " (for [[u st] (:units s)] (str (if (= "idea" u) "intellij" "display") " " (if (= "active" st) (green st) (red st)))))))
                       (when-let [i (:image s)] (kv "image" (str "built " (since (:created i)))))
                       ""
                       (dim "Stopping keeps your home, the work folder and the IDE's state.")
                       (dim "Containers next to the VM keep running.")]))))

(defn- vm-row [s]
  (let [in (:inside s)
        chip (fn [w] (c "2;7" (str " " w " ")))]
    (case (:state s)
      :running (row "vm" (str (line (green "●") "vm" (green "running")
                                    (str/join " · " (remove nil? [(some-> (:uptime in) dur) (some-> (get-in in [:memory :used]) gb)])))
                              "  " (chip "stop"))
                    :hint "⏎ stop the vm" :preview (vm-preview s)
                    :act {:run vm/down! :title "stopping the vm" :confirm "stop the vm? (IntelliJ closes; home, work folder and IDE state are kept)" :yes-first true})
      :starting (row "vm" (str (line (yellow "◌") "vm" (yellow "starting") "the page does not answer yet") "  " (chip "stop"))
                     :hint "⏎ stop the vm" :preview (vm-preview s)
                     :act {:run vm/down! :title "stopping the vm" :confirm "stop the vm while it starts?" :yes-first false})
      :broken (row "vm" (line (red "✗") "vm" (red "broken") (:words s))
                   :hint "⏎ restart · logs · stop" :preview (vm-preview s)
                   :act {:title "the vm is broken"
                         :menu [["restart the vm" {:run #(do (vm/down!) (vm/up! [])) :title "restarting the vm"}]
                                ["logs" {:tty #(vm/logs! [])}]
                                ["stop the vm" {:run vm/down! :title "stopping the vm"}]]})
      (row "vm" (str (line (dim "○") "vm" "stopped" "") "  " (c "7" " start "))
           :hint "⏎ start the vm"
           :preview (str/join "\n" [(kv "state" (:words s))
                                    (kv "work" (home-rel (get-in s [:settings :work])))
                                    (kv "repos" (home-rel (get-in s [:settings :repos])))
                                    (kv "desktop" (get-in s [:settings :desktop]))
                                    (when-let [i (:image s)] (kv "image" (str "built " (since (:created i)))))
                                    "" (dim "The first start after a build copies the image: a few minutes.")
                                    (dim "After that about 20 seconds.")])
           :act {:run #(vm/up! []) :title "starting the vm"}))))

(def ^:private keys-help
  (str/join "\n" ["On a Mac, in the browser:"
                  (kv "Cmd" "works as Ctrl  (Cmd+C, Cmd+V, Cmd+S, Cmd+Z)")
                  (kv "Settings" "Cmd+Alt+S")
                  (kv "Alt+1..9" "IntelliJ's tool windows (the xfce desktop takes no keys)")
                  (kv "Cmd+W" "closes the browser window, not the editor tab: use Cmd+F4")
                  ""
                  (dim "Closing the browser changes nothing: the session lives in the VM.")]))

(defn- ide-row [s]
  (case (:state s)
    :running (row "ide" (line (green "●") "intellij" (green "ready") (vm/url))
                  :hint "⏎ open it in the browser"
                  :preview (str (kv "page" (vm/url)) "\n" (kv "opens" (if vm/mac? "Chrome, as an app window" "your browser")) "\n\n" keys-help)
                  :act {:quiet vm/open!})
    :starting (row "ide" (line (yellow "◌") "intellij" (yellow "starting") "") :hint "wait a moment" :preview "IntelliJ is starting.")
    :broken (row "ide" (line (red "✗") "intellij" (red "down") (str/join ", " (for [[u st] (:units s)] (str u " " st))))
                 :hint "⏎ logs" :preview "The page does not answer." :act {:tty #(vm/logs! [])})
    (row "ide" (line " " "intellij" "" "") :hint "start the vm first" :preview "IntelliJ runs in the VM and shows in your browser.")))

(defn- folder-act
  "Asks for another work or repos folder; a running VM is restarted to mount it."
  [k title]
  {:ask (str title " (a folder on this machine)") :default (home-rel (get (vm/settings) k (if (= k :work) vm/default-work vm/default-repos)))
   :then (fn [answer]
           (let [d (str (fs/absolutize (fs/expand-home answer)))]
             (cond (and (= k :repos) (not (fs/directory? d))) {:quiet #(println "no such folder:" d)}
                   :else {:run (fn []
                                 (fs/create-dirs d)
                                 (vm/save-settings! {k d})
                                 (println (name k) "folder:" d)
                                 (when (vm/running?) (vm/down!) (vm/up! [])))
                          :title (str "using " (home-rel d))
                          :confirm (when (vm/running?) (str "mount " (home-rel d) "? the vm restarts"))
                          :yes-first true})))})

(defn- mount-rows [s]
  (let [st (:settings s)
        by (into {} (for [m (:mounts s)] [(:to m) m]))
        work (or (some #(when (and (= "bind" (:kind %)) (= (:to %) (:from %)) (not (str/starts-with? (:to %) "/run"))) (:from %)) (:mounts s))
                 (:work st))
        repos (or (:from (by "/repos")) (:repos st))
        n-work (count (get-in s [:inside :work]))
        n-repos (count (:host-repos s))]
    (remove nil?
            [(row "m.work" (line2 "/work" (str (home-rel work) (when (up? s) (str "  ·  " (plural n-work "repo")))))
                  :hint "⏎ use another folder"
                  :preview (str (kv "in the vm" "/work") "\n" (kv "on the host" (home-rel work)) "\n\n"
                                "Your work folder: a folder of this machine, at the same path inside the VM\n"
                                "(/work is a link to it). Containers started from the VM run next to it and\n"
                                "can mount project files, because the path is the same on both sides.")
                  :act (folder-act :work "work folder"))
             (row "m.repos" (line2 "/repos" (str (home-rel repos) "  ·  " (plural n-repos "repo")))
                  :hint "⏎ use another folder"
                  :preview (str (kv "in the vm" "/repos") "\n" (kv "on the host" (home-rel repos)) "\n\n"
                                "Bare git repositories of this machine. A repo imported into the VM is a clone\n"
                                "of one of them: a push from the VM lands here. No credentials go into the VM.")
                  :act (folder-act :repos "folder with the bare repos"))
             (row "m.home" (line2 "/home/dev" (str "volume " vm/home-volume))
                  :hint "your home in the vm"
                  :preview (str "A podman volume: IntelliJ's settings, indexes and caches, your shell history.\nIt survives stop, start and a new image."))
             (row "m.podman" (line2 "podman" "the host's: containers start next to the vm")
                  :hint "podman in the vm"
                  :preview (str "`podman` inside the VM talks to this machine's podman.\nA container started there (Floci, a database, a service) runs next to the VM,\nnot inside it, and shows up below."))
             (when (seq (:cas s))
               (row "m.ca" (line2 "/ca" (plural (count (:cas s)) "company certificate"))
                    :hint "company CAs" :preview (str/join "\n" (:cas s))))])))

(def ^:private own-calls
  "The menu's own inventory calls as Floci logs them: left out of the log shown beside a row."
  #"action: (ListQueues|GetQueueAttributes|ListEventBuses|DescribeParameters|ListSecrets|ListClusters|DescribeLogGroups|ListTables|DescribeTable)$")

(defn- short-log
  "A log line made short for the side pane: no date before the time, a dotted logger name in brackets
  by its last part."
  [l]
  (-> (plain l)
      (str/replace #"^\d{4}-\d{2}-\d{2}[T ]" "")
      (str/replace #"\[(?:[A-Za-z_][\w$]*\.)+([A-Za-z_][\w$]*)\]" "[$1]")))

(defn- logs-of [n]
  (let [r (vm/run 8000 "podman" "logs" "--tail" "300" n)]
    (->> (str/split-lines (str (:out r) (:err r))) (remove #(re-find own-calls %)) (take-last 40) (map short-log) (str/join "\n"))))

(defn- container-menu [{:keys [name state kind]} aws]
  (let [running (= "running" state)]
    {:title name
     :menu (vec (remove nil?
                        [(when (and (= :aws kind) aws) ["aws resources" {:view "aws"}])
                         ["logs" {:tty #(vm/tty "sh" "-c" (str "podman logs --tail 3000 '" name "' 2>&1 | less -R +G"))}]
                         (when running ["restart" {:run #(vm/container! "restart" name) :title (str "restarting " name)}])
                         (when running ["shell in it" {:tty #(vm/tty "podman" "exec" "-it" name "sh" "-c" "command -v bash >/dev/null && exec bash || exec sh")}])
                         (if running
                           ["stop" {:run #(vm/container! "stop" name) :title (str "stopping " name) :confirm (str "stop " name "?") :yes-first false}]
                           ["start" {:run #(vm/container! "start" name) :title (str "starting " name)}])]))}))

(defn- aws-summary [aws]
  (let [rs (:resources aws)] (plural (reduce + (map #(count (:items %)) rs)) "resource")))

(defn- aws-preview [aws]
  (str/join "\n"
            (concat [(str (bold "Floci") " " (:version aws) (dim (str "  a local AWS on port " (:port aws)))) ""]
                    (if (empty? (:resources aws))
                      [(dim "no resources yet")]
                      (for [{:keys [label items]} (:resources aws)]
                        (str (cyan (pad label 12)) (pad (str (count items)) 4)
                             (dim (cut (str/join "  " (map :name items)) (max 30 (- *cols* 20))))))))))

(defn- sibling-rows [s tails]
  (for [{:keys [name short kind state status ports image] :as x} (:siblings s)
        :let [running (= "running" state)
              aws (when (and (= :aws kind) running (some #{(get-in s [:aws :port])} ports)) (:aws s))]]
    (row (str "c." name)
         (line (if running (green "●") (red "○")) short
               (case kind :aws "aws" :database "database" :lambda "lambda" "service")
               (str/join " · " (remove str/blank? [(when (seq ports) (str/join " " (map #(str ":" %) (take 3 ports))))
                                                   (str/lower-case (str status))
                                                   (when aws (aws-summary aws))])))
         :hint (if (= :lambda kind) "⏎ logs · stop" "⏎ logs · restart · stop …")
         :preview (str/join "\n" (concat [(kv "container" name) (kv "image" image) (kv "state" (str/lower-case (str status)))
                                          (when (seq ports) (kv "ports" (str/join " " ports))) ""]
                                         (when aws [(aws-preview aws) ""])
                                         [(dim "── last lines of its log ──")]
                                         (map #(cut (plain %) (max 40 (- *cols* 2))) (take-last 25 (str/split-lines (or (get tails name) ""))))))
         :act (container-menu x aws))))

(defn- repo-state [{:keys [dirty ahead behind upstream?]}]
  (let [parts (remove nil? [(when (pos? dirty) (yellow (str dirty " changed")))
                            (when (pos? ahead) (cyan (str ahead " to push")))
                            (when (pos? behind) (str behind " behind"))
                            (when-not upstream? "no upstream")])]
    (if (seq parts) (str/join " · " parts) "clean")))

(defn- branch-menu
  "The other branches of a repo in the VM as a list to switch to; nil when there is no other."
  [{:keys [name branches remote-branches branch]}]
  (let [remote-only (remove (set branches) (map #(str/replace % #"^origin/" "") remote-branches))
        items (vec (concat (for [b branches :when (not= b branch)] [b {:run #(vm/switch! name b) :title (str name ": switching to " b)}])
                           (for [b remote-only] [(str b (dim "  (on the host)")) {:run #(vm/switch! name b) :title (str name ": switching to " b)}])))]
    (when (seq items) {:menu items :title (str name ": switch from " branch " to")})))

(defn- work-rows [s]
  (let [rs (get-in s [:inside :work])]
    (if (empty? rs)
      [(row "w.none" (dim "   none yet: pick one below") :hint "nothing imported" :preview "No repository is in the VM yet.\nPick one from the list below.")]
      (for [{:keys [name branch dirty ahead last status log] :as r} rs]
        (row (str "w." name)
             (str "  " (bold (pad (cut name *w*) *w*)) "  " (pad (cut (str branch) 22) 22) " " (repo-state r) (dim (str "  " last)))
             :hint "⏎ open in IntelliJ · shell · pull · push · branch …"
             :preview (str/join "\n" (concat [(str (bold name) "  " (cyan (str branch)) "  " (repo-state r))
                                              (dim (str "/work/" name)) ""]
                                             (when (seq status) (concat status [""]))
                                             log))
             :act {:title name
                   :menu (vec (remove nil?
                                      [["open in IntelliJ" {:quiet #(vm/open-in-ide! name)}]
                                       ["shell here" {:tty #(vm/shell! name)}]
                                       ["pull" {:run #(vm/git! name "pull" "--ff-only") :title (str "pulling " name)}]
                                       (when (pos? ahead) [(str "push " (plural ahead "commit") " to the host") {:run #(vm/git! name "push" "origin" "HEAD") :title (str "pushing " name)
                                                                                                 :done (str "pushed " (plural ahead "commit") " of " name " to the host")}])
                                       (when-let [m (branch-menu r)] ["switch branch" m])
                                       ["remove from the vm" {:run #(vm/remove-repo! name) :title (str "removing " name)
                                                              :confirm (str "remove /work/" name " from the vm?"
                                                                            (when (or (pos? dirty) (pos? ahead))
                                                                              (str " LOST: " (str/join ", " (remove nil? [(when (pos? dirty) (plural dirty "changed file")) (when (pos? ahead) (plural ahead "unpushed commit"))])))))
                                                              :yes-first (not (or (pos? dirty) (pos? ahead)))}]]))})))))

(defn- available-rows [s]
  (let [in-vm (set (map :name (get-in s [:inside :work])))
        details (into {} (for [r (get-in s [:inside :repos])] [(:name r) r]))
        names (remove in-vm (:host-repos s))
        running (= :running (:state s))]
    (cond
      (empty? (:host-repos s))
      [(row "a.none" (dim (str "   no bare repos in " (home-rel (get-in s [:settings :repos]))))
            :hint "⏎ use another folder" :preview "The repos folder holds bare git repositories (NAME.git).\nChoose the folder where yours are."
            :act (folder-act :repos "folder with the bare repos"))]
      (empty? names)
      [(row "a.all" (dim "   all of them are in the vm") :hint "" :preview "")]
      :else
      (for [n names :let [{:keys [branch branches last log]} (details n)]]
        (row (str "a." n)
             (str "  " (pad (cut n *w*) *w*) "  " (dim (str (pad (cut (str branch) 22) 22) " " (when branches (str (plural branches "branch") "  ")) last)))
             :hint (if running "⏎ import into the vm" "start the vm to import")
             :preview (str/join "\n" (concat [(str (bold n) "  " (cyan (str branch)) (when branches (dim (str "  " (plural branches "branch")))))
                                              (dim (str (home-rel (get-in s [:settings :repos])) "/" n ".git")) ""]
                                             log))
             :act (when running
                    {:run #(vm/import! n) :title (str "importing " n) :confirm (str "import " n " into the vm?") :yes-first true}))))))

(defn- warning-rows [s]
  (remove nil?
          [(when (and (:image s) (:stale? (:image s)))
             (row "warn.image" (str (yellow "⚠") " " (yellow "the image is older than its plugins, CAs or files"))
                  :hint "⏎ build it again"
                  :preview "What the image is built from changed since it was built\n(a plugin zip, a company CA, or devkit itself).\n\nThe build takes a few minutes; the running VM keeps the old image until it is restarted."
                  :act {:run #(vm/build! []) :title "building the image" :then-restart true}))
           (when (and (:tls-proxy? s) (empty? (:cas s)))
             (row "warn.ca" (str (yellow "⚠") " " (yellow "TLS is rewritten here (a company proxy): its CA is not installed"))
                  :hint "⏎ install the CA"
                  :preview "A download check failed on the certificate: something between this machine and\nthe internet signs TLS with its own CA (Zscaler does).\n\nGive the root CA as a PEM file. It goes into the podman machine, the image and the\nVM (git, curl, terraform, both JDKs, chromium). Nothing is read from the keychain."
                  :act {:ask "PEM file(s) or a folder of them" :default "" :then (fn [a] (when (seq a) {:run #(vm/ca-install! (str/split a #"\s+")) :title "installing the company CA"}))}))]))

(defn- vm-rows [s]
  (let [b (blocker s)
        tails (:tails s)
        body (concat [(hdr "m" "mounted")] (mount-rows s)
                     (when (seq (:siblings s)) (cons (hdr "c" "running next to the vm") (sibling-rows s tails)))
                     [(hdr "w" "repos in the vm")] (if (up? s) (work-rows s) [(row "w.off" (dim "   the vm is not running") :hint "" :preview "")])
                     [(hdr "a" "repos available")] (available-rows s))]
    (if b
      (concat [b] (map (partial grey (str "first: " (str/replace (plain (:text b)) #"^\S+\s+" ""))) (concat [(vm-row s) (ide-row s)] body)))
      (concat (warning-rows s) [(vm-row s) (ide-row s)] body))))

;; -- the setup tab

(defn- setup-rows [s]
  (let [m (:machine s) i (:image s) st (:settings s)
        d (or (get-in s [:inside :desktop]) (:desktop st))
        other (if (= "i3" d) "xfce" "i3")]
    (remove nil?
            [(hdr "p" "podman")
             (cond
               (not (vm/podman?)) (row "s.podman" (line (red "✗") "podman" (red "missing") (if vm/mac? "brew install podman" "install it with your package manager")) :hint "" :preview "")
               m (row "s.machine" (line (if (:running? m) (green "●") (red "○")) "podman machine" (if (:running? m) (green "running") "stopped")
                                        (str (:cpus m) " CPUs · " (gb (:memory m)) " · " (gb (:disk m)) " disk"))
                      :hint (if (:running? m) "⏎ resize · stop" "⏎ start")
                      :preview (str (kv "machine" (:name m)) "\n" (kv "cpus" (:cpus m)) "\n" (kv "memory" (gb (:memory m))) "\n" (kv "disk" (gb (:disk m)))
                                    "\n\nThe small Linux machine podman runs containers in on a Mac. The VM, Floci and every\nservice share its memory: IntelliJ alone takes 3 to 4 GB.")
                      :act (if (:running? m)
                             {:title "podman machine"
                              :menu [["give it 6 CPUs and 12 GB" {:run #(vm/machine-resize! 6 12) :title "resizing the podman machine" :confirm "stop the podman machine (the vm and every container stop), resize, start?" :yes-first false}]
                                     ["give it 8 CPUs and 16 GB" {:run #(vm/machine-resize! 8 16) :title "resizing the podman machine" :confirm "stop the podman machine (the vm and every container stop), resize, start?" :yes-first false}]
                                     ["stop the podman machine" {:run vm/machine-stop! :title "stopping the podman machine" :confirm "stop the podman machine? the vm and every container stop" :yes-first false}]]}
                             {:run vm/machine-start! :title "starting the podman machine"}))
               :else (row "s.podman" (line (green "●") "podman" (green "ready") "no podman machine needed here")
                          :hint "" :preview "On Linux podman needs no machine."))
             (hdr "i" "image")
             (row "s.image" (if (#{:no-podman :no-machine :machine-stopped} (:state s))
                              (line " " "image" "" "podman must run to look at it")
                              (line (cond (nil? i) (red "○") (:stale? i) (yellow "●") :else (green "●")) "image"
                                    (cond (nil? i) "not built" (:stale? i) (yellow "outdated") :else (green "built"))
                                    (when i (str (since (:created i)) " · " (gb (:size i))))))
                  :hint (if i "⏎ build it again" "⏎ build it")
                  :preview (str "Arch Linux, IntelliJ IDEA " vm/idea-version ", the desktop, JDK 25 and 21, maven, node, git,\nterraform, podman, babashka, chromium.\n\n"
                                (kv "plugins" (str/join "  " (or (:plugins i) (:zips s)))) "\n"
                                (kv "CAs" (if (seq (:cas s)) (str/join "  " (:cas s)) "none")) "\n\n"
                                (dim "A build takes about 10 minutes the first time, less after."))
                  :act (when (vm/podman?) {:run #(vm/build! []) :title "building the image" :then-restart true
                                           :confirm (when i "build the image again?") :yes-first true}))
             (row "s.plugins" (line2 "plugins" (if (seq (:zips s)) (str/join "  " (map #(str/replace % #"\.zip$" "") (:zips s))) (red "no zips")))
                  :hint "IDE plugins in the image"
                  :preview (str "The plugin zips that go into the image, from\n\n  " (home-rel vm/state) "/plugins/\n  " (home-rel vm/state) "/artifacts/\n\n"
                                (when i (str (kv "in the image" (str/join "  " (:plugins i))) "\n\n"))
                                (dim "Put a newer zip there and build the image again.")))
             (row "s.ca" (line2 "company CAs" (if (seq (:cas s)) (str/join "  " (:cas s)) "none installed"))
                  :hint "⏎ install a PEM file"
                  :preview "Behind a TLS proxy (Zscaler) downloads fail until its root CA is trusted.\n\nGive the PEM file(s): they go into the podman machine (image pulls), into the image and\nthe running VM: git, curl, terraform, both JDKs, chromium.\nNothing is read from the keychain."
                  :act {:ask "PEM file(s) or a folder of them" :default ""
                        :then (fn [a] (when (seq a) {:run #(vm/ca-install! (str/split a #"\s+")) :title "installing company CAs"}))})
             (hdr "v" "vm")
             (row "s.desktop" (line " " "desktop" d (if (= "i3" d) "tiling, Alt is its key: it takes Alt+1..9 from IntelliJ" "a panel and the mouse; every key reaches IntelliJ"))
                  :hint (str "⏎ switch to " other)
                  :preview (str (kv "xfce" "windows with title bars, a panel at the bottom (IntelliJ, terminal,\n           chromium, files). It takes no keys at all, so IntelliJ's shortcuts work.") "\n\n"
                                (kv "i3" "the author's tiling setup. Alt is its key: Alt+1..9 are workspaces,\n           Alt+t a terminal, Alt+c chromium. IntelliJ loses Alt+digits.") "\n\n"
                                (dim "Switching keeps IntelliJ open."))
                  :act {:run #(vm/desktop! other) :title (str "switching to " other)})
             (row "s.work" (line2 "work folder" (home-rel (:work st))) :hint "⏎ use another folder"
                  :preview "Where your projects live: a folder of this machine, mounted in the VM at the same path." :act (folder-act :work "work folder"))
             (row "s.repos" (line2 "repos folder" (home-rel (:repos st))) :hint "⏎ use another folder"
                  :preview "The folder with the bare repositories that can be imported into the VM." :act (folder-act :repos "folder with the bare repos"))
             (hdr "t" "tools")
             (row "s.shell" (line2 "shell" "a terminal in the vm, as dev, in /work")
                  :hint (if (up? s) "⏎ open it" "the vm is not running") :preview "A login shell inside the VM. `exit` comes back here."
                  :act (when (up? s) {:tty vm/shell!}))
             (row "s.logs" (line2 "logs" "the journal of IntelliJ and the display")
                  :hint (if (up? s) "⏎ show them" "the vm is not running") :preview "The last 200 lines of the VM's journal for the IDE and the display."
                  :act (when (up? s) {:tty #(do (vm/logs! []) (print (dim "\n⏎ back ")) (flush) (read-line))}))])))

(defn- aws-rows [s]
  (let [rs (get-in s [:aws :resources])]
    (if (empty? rs)
      [(row "aws.none" (dim "  nothing there yet") :hint "" :preview "")]
      (let [w (reduce max 20 (for [r rs i (:items r)] (min 40 (count (:name i)))))]
        (apply concat
               (for [{:keys [label region items]} rs]
                 (cons (hdr (str "aws." label "." region) (str label "  " region))
                       (for [{n :name d :detail} items]
                         (row (str "aws." label "." region "." n)
                              (str "  " (cyan (pad label 12)) " " (pad (cut n w) w) "  " (dim (or d "")))
                              :hint label
                              :preview (str/join "\n" (remove nil? [(bold n) "" (kv "service" label) (kv "region" region) (when (seq d) (kv "now" d))])))))))))))

(defn- name-width [s]
  (reduce max 12 (map #(min 26 (count %)) (concat (:host-repos s) (map :name (get-in s [:inside :work])) (map :short (:siblings s))))))

(defn views
  "Every view's rows for a snapshot: {\"vm\" [..] \"setup\" [..] \"aws\" [..]}."
  [s cols]
  (binding [*w* (name-width s) *cols* cols]
    {"vm" (vec (vm-rows s)) "setup" (vec (setup-rows s)) "aws" (vec (aws-rows s))}))

;; ---- the cache fzf reads ----------------------------------------------------------------------------------

(def ^:private tui-dir (str vm/state "/tui"))
(defn- cache [& xs] (str (apply fs/path tui-dir "cache" xs)))
(defn- safe [id] (str/replace id #"[^A-Za-z0-9._-]" "_"))

(defn- put! [f s]
  (let [t (str f "." (.pid (java.lang.ProcessHandle/current)) ".tmp")]
    (spit t s)
    (fs/move t f {:replace-existing true :atomic-move true})))

(def ^:private tabs ["vm" "setup"])
(def ^:private toast (atom nil))

(defn- header
  "The tab bar and, for a few seconds, what the last action said."
  [view]
  (let [t @toast
        tab (fn [n] (if (= n view) (c "1;7" (str " " n " ")) (dim (str " " n " "))))]
    (str " " (bold "devkit") "   " (str/join " " (map tab tabs))
         (when (= "aws" view) (str "  " (c "1;7" " aws resources ")))
         "\n " (if (and t (< (- (System/currentTimeMillis) (:at t)) 9000)) (:text t) ""))))

(defn- write-views! [vs]
  (fs/create-dirs (cache))
  (doseq [[_ rs] vs {:keys [id preview hint]} rs :when (not (str/starts-with? id "h."))]
    (put! (cache (str (safe id) ".txt")) (or preview ""))
    (put! (cache (str (safe id) ".hint")) (if (seq hint) (str " " hint " ") "")))
  (doseq [[v rs] vs]
    (put! (cache (str "rows." v)) (str/join "\n" (for [{:keys [id header text]} rs] (str (safe id) "\t" (or header "") "\t" (or text "")))))
    (put! (cache (str "header." v)) (header v))))

;; ---- fzf --------------------------------------------------------------------------------------------------

(def ^:private look
  ["--ansi" "--layout=reverse" "--height=100%" "--no-clear" "--info=hidden" "--cycle" "--no-sort" "--no-mouse"
   "--pointer=▌" "--gutter= " "--prompt=  " "--highlight-line" "--no-separator" "--footer-border=none" "--preview-wrap-sign=  "
   "--color=bg+:236,fg+:255,gutter:-1,pointer:39,prompt:39,hl:179,hl+:179,border:240,label:250,header:-1,query:255,ghost:240,footer:244"])

(def ^:private api-key "the key fzf wants on its --listen port" (str (java.util.UUID/randomUUID)))

(defn- fzf
  "Lines in (id TAB header TAB text), a pick out: {:key K :id ID :query Q} (key \"\" = enter), nil when left."
  [input & args]
  (let [r @(apply p/process {:in input :out :string :err :inherit :extra-env {"FZF_API_KEY" api-key}}
                  (concat ["fzf"] look ["--delimiter=\t" "--with-nth=2.." "--nth=2" "--tabstop=1" "--print-query"]
                          ;; always --expect: the output is then always three lines (query, key, pick)
                          (let [args (remove nil? (flatten args))]
                            (if (some #(str/starts-with? % "--expect") args) args (cons "--expect=f12" args)))))
        [q k l] (str/split-lines (:out r))]
    (case (long (:exit r))
      0 {:key (or k "") :id (first (str/split (or l "") #"\t")) :query q}
      1 {:key (or k "") :id nil :query q}
      nil)))

(defn- preview-window
  "The right half on a wide terminal, the lower part on a narrow one."
  [wide]
  (if wide "right,50%,border-rounded,wrap,noinfo" "down,45%,border-rounded,wrap,noinfo"))

(def ^:dynamic *beside* "the preview file shown next to a short list (the row it belongs to)" nil)

(defn- pick
  "A short list: [[id label]..] -> the id picked, nil when left."
  [title items]
  (:id (fzf (str/join "\n" (for [[id label] items] (str id "\t\t  " label)))
            "--exact" "--no-input" "--header" (str " " (bold "devkit") "   " (c "1;7" (str " " title " ")) "\n ") "--header-first"
            "--footer" (dim "  ⏎ choose · esc back")
            (when *beside* ["--preview" (str "cat " *beside* " 2>/dev/null")
                            "--preview-window" (preview-window (let [[_ c] (term-size)] (>= c 120)))])
            "--bind" "esc:abort,left:abort,q:abort")))

(defn- confirm [question yes-first]
  (= "yes" (pick question (if yes-first [["yes" "yes"] ["no" "no"]] [["no" "no"] ["yes" "yes"]]))))

(defn- ask
  "A line of text from the user; nil when left."
  [prompt default]
  (let [r @(apply p/process {:in "" :out :string :err :inherit}
                  (concat ["fzf"] look ["--print-query" "--prompt" (str "  " prompt ": ") "--query" (or default "")
                                        "--header" "\n" "--header-first" "--footer" (dim "  ⏎ ok · esc back") "--bind" "esc:abort"]))]
    (when (#{0 1} (:exit r)) (some-> (first (str/split-lines (:out r))) str/trim not-empty))))

;; ---- running an action ------------------------------------------------------------------------------------

(defn- say! [ok text] (reset! toast {:at (System/currentTimeMillis) :text (str (if ok (green "✓") (red "✗")) " " text)}))

(def ^:private child? "true while a program owns the terminal: ctrl-c is its, not ours" (atom false))

(defn- stty [& args]
  (p/shell {:continue true :out :string :err :string} "sh" "-c" (str "stty " (str/join " " args) " </dev/tty")))

(defn- on-terminal!
  "Leaves the menu's screen, runs f on the plain terminal, comes back."
  [f]
  (print "\033[?1049l\033[?25h") (flush)
  (stty "sane")
  (reset! child? true)
  (try (f)
       (catch Exception e (println (red (str "failed: " (ex-message e)))) (print (dim "⏎ back ")) (flush) (read-line))
       (finally (reset! child? false) (stty "-echo" "-icanon" "min" "1"))))

(defn- progress!
  "Runs f with its output caught: a spinner, the title, the time, the last lines. The whole output goes
  to <state>/logs. -> {:ok bool :last LINE :secs N}; a failure stays on the screen until enter."
  [title f]
  (let [sw (java.io.StringWriter.)
        t0 (System/currentTimeMillis)
        fut (future (binding [*out* sw *err* sw]
                      (try (f) {:ok true}
                           (catch Exception e {:ok false :error (or (ex-message e) (str e))}))))
        [rows cols] (term-size)
        n (max 3 (min 16 (- rows 8)))
        frames "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
        tail (fn [k] (->> (str/split (let [s (str sw)] (subs s (max 0 (- (count s) 8000)))) #"[\r\n]+")
                          (map plain) (remove str/blank?) (take-last k)))
        ;; a build says STEP 8/18: shown beside the title
        step (fn [] (when-let [[_ a b] (last (re-seq #"STEP (\d+)/(\d+)" (str sw)))] (str "step " a " of " b " · ")))
        draw (fn [mark]
               (print "\033[H")
               (print (str "\033[K\n\033[K  " mark " " (bold title) "   " (dim (str (step) (dur (quot (- (System/currentTimeMillis) t0) 1000)))) "\n\033[K\n"))
               (doseq [l (tail n)] (print (str "\033[K    " (dim (cut l (- cols 6))) "\n")))
               (print "\033[J") (flush))]
    (print "\033[?1049h\033[H\033[2J\033[?25l") (flush)
    (loop [i 0]
      (if (realized? fut)
        (let [r @fut
              secs (quot (- (System/currentTimeMillis) t0) 1000)
              log (str vm/logs-dir "/" (str/replace (str/lower-case title) #"[^a-z0-9]+" "-") "-"
                       (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "MMdd-HHmmss")) ".log")]
          (fs/create-dirs vm/logs-dir)
          (spit log (str sw))
          (if (:ok r)
            (assoc r :secs secs :last (last (tail 1)))
            (do (print "\033[H\033[2J")
                (println (str "\n  " (red "✗") " " (bold title) "   " (dim (dur secs)) "\n"))
                (doseq [l (tail 12) part (map #(apply str %) (partition-all (max 20 (- cols 6)) l))]
                  (println (str "    " (dim part))))
                (println (str "\n  " (red (str title ": " (:error r)))))
                (println (dim (str "  the whole output: " (home-rel log))))
                (print (str "\n  " (dim "⏎ back "))) (flush)
                (print "\033[?25h") (flush)
                (read-line)
                (assoc r :secs secs))))
        (do (draw (cyan (str (nth frames (mod i (count frames))))))
            (Thread/sleep 100)
            (recur (inc i)))))))

(declare do-act!)

(defn- do-act!
  "Does a row's action. -> nil when nothing was done (left with esc), {:did true}, or {:view NAME} to
  show another list."
  [act]
  (cond
    (nil? act) nil
    (:view act) act
    (:menu act) (let [items (:menu act)]
                  (when-let [i (pick (or (:title act) "choose") (map-indexed (fn [i [l]] [(str i) l]) items))]
                    (do-act! (second (nth items (parse-long i))))))
    (:ask act) (when-let [a (ask (:ask act) (:default act))] (do-act! ((:then act) a)))
    (:tty act) (do (on-terminal! (:tty act)) {:did true})
    (:quiet act) (let [o (with-out-str (try ((:quiet act)) (catch Exception e (println "failed:" (ex-message e)))))
                       l (last (remove str/blank? (str/split-lines o)))]
                   (say! (not (str/starts-with? (str l) "failed:")) (or l "done"))
                   {:did true})
    (:run act) (when (or (nil? (:confirm act)) (confirm (:confirm act) (:yes-first act)))
                 (let [r (progress! (:title act) (:run act))]
                   (say! (:ok r) (if (:ok r)
                                   (str (or (:done act) (:last r) (:title act)) (when (< 2 (:secs r)) (dim (str "  · " (dur (:secs r))))))
                                   (str (:title act) " failed: " (:error r))))
                   (when (and (:ok r) (:then-restart act) (vm/running?)
                              (confirm "restart the vm now, on the new image?" true))
                     (let [r2 (progress! "restarting the vm" #(do (vm/down!) (vm/up! [])))]
                       (say! (:ok r2) (if (:ok r2) "the vm runs on the new image" (str "restart failed: " (:error r2))))))
                   {:did true}))))

;; ---- the loop ---------------------------------------------------------------------------------------------

(defn- tls-proxy?
  "True when a TLS download check fails on the certificate itself: something signs TLS with a CA this
  machine's programs do not know (a company proxy)."
  [url]
  (try (http/head url {:timeout 5000 :throw false}) false
       (catch Exception e
         (boolean (re-find #"(?i)SSLHandshake|PKIX|certification path|unable to find valid cert"
                           (str (class e) " " (ex-message e) " " (some-> (ex-cause e) str)))))))

(defn- free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(def ^:private aws-seen "the last inventory of the local AWS: {:at MS :port P :aws MAP}" (atom nil))

(defn- aws-now-and-then
  "The local AWS's inventory, asked at most once a minute (each list call is a line in Floci's log), and
  after the first answer only in the regions that had something."
  [port]
  (let [{:keys [at aws] p :port} @aws-seen]
    (if (and at (= p port) (< (- (System/currentTimeMillis) at) 60000))
      aws
      (let [regions (when (= p port) (seq (distinct (map :region (:resources aws)))))
            a (vm/aws port regions)]
        (reset! aws-seen {:at (System/currentTimeMillis) :port port :aws a})
        a))))

(defn- snap!
  "A snapshot with what only the menu adds: the log tails of the containers next to the VM."
  [extra]
  (let [s (vm/snapshot aws-now-and-then)
        tails (into {} (pmap (fn [x] [(:name x) (logs-of (:name x))]) (remove #(= :lambda (:kind %)) (:siblings s))))]
    (merge s extra {:tails tails})))

(defn run!
  "The menu."
  []
  (when-not (fs/which "fzf")
    (println "The menu needs fzf." (if vm/mac? "Install it:  brew install fzf" "Install it with your package manager."))
    (println "Without it: bb vm:status, bb vm:build, bb vm:up, bb vm:down, bb vm:open (bb tasks lists them).")
    (System/exit 1))
  (fs/delete-tree (cache))
  (fs/create-dirs (cache))
  (let [state (atom {:done false :showing nil :views nil})
        kick (java.util.concurrent.LinkedBlockingQueue.)
        port (free-port)
        tls (future (tls-proxy? "https://download.jetbrains.com/"))
        wide? (fn [] (let [[_ c] (term-size)] (>= c 120)))
        cols (fn [] (let [[_ c] (term-size)] (if (wide?) (- (quot c 2) 4) (- c 6))))
        refresh! (fn []
                   (let [s (snap! {:tls-proxy? (and (realized? tls) @tls)})
                         vs (views s (cols))]
                     (write-views! vs)
                     (swap! state assoc :views vs :snapshot s)))
        saved (str/trim (:out (stty "-g")))
        leave (fn []
                ;; runs as a shutdown hook too, where babashka's process pool is closed: a plain ProcessBuilder
                (try (-> (ProcessBuilder. ^java.util.List ["sh" "-c" (str "stty " saved " </dev/tty")]) .start .waitFor) (catch Exception _))
                (print "\033[?1049l\033[?25h") (flush))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable leave))
    (sun.misc.Signal/handle (sun.misc.Signal. "INT")
                            (reify sun.misc.SignalHandler (handle [_ _] (when-not @child? (System/exit 130)))))
    ;; keys typed while the menu is not reading must not show up on the screen
    (stty "-echo" "-icanon" "min" "1")
    (print (str "\033[?1049h\033[H\033[2J\033[?25l\n  " (dim "looking at podman…"))) (flush)
    (try (refresh!)
         (catch Throwable e
           (leave)
           (binding [*out* *err*] (println "bb vm cannot look at podman:" (or (ex-message e) (str e))))
           (System/exit 1)))
    (future
      (loop []
        (.poll kick 4 java.util.concurrent.TimeUnit/SECONDS)
        (when-not (:done @state)
          (try (refresh!)
               (when-let [v (:showing @state)]
                 (http/post (str "http://127.0.0.1:" port) {:headers {"x-api-key" api-key} :timeout 2000 :throw false
                                                               ;; the header first: fzf drops a header change that comes after reload in one request
                                                               :body (str "transform-header(cat " (cache (str "header." v)) ")"
                                                                          ;; the row under the cursor may have changed what enter does
                                                                          "+transform-preview-label(cat " (cache) "/{1}.hint 2>/dev/null)"
                                                                          "+reload(cat " (cache (str "rows." v)) ")+refresh-preview")}))
               (catch Exception _))
          (recur))))
    (try
      (loop [view "vm" last-id nil]
        (let [text (slurp (cache (str "rows." view)))
              ids (map #(first (str/split % #"\t" 2)) (str/split-lines text))
              ;; the row the cursor was on; a repo that changed section (imported, removed) is followed there
              twin (when last-id (cond (str/starts-with? last-id "a.") (str "w." (subs last-id 2))
                                       (str/starts-with? last-id "w.") (str "a." (subs last-id 2))))
              pos (or (some (fn [[i id]] (when (= last-id id) (inc i))) (map-indexed vector ids))
                      (some (fn [[i id]] (when (= twin id) (inc i))) (map-indexed vector ids))
                      (some (fn [[i id]] (when-not (str/starts-with? id "h.") (inc i))) (map-indexed vector ids))
                      1)
              sub? (not (some #{view} tabs))
              _ (swap! state assoc :showing view)
              r (fzf text
                     "--exact" "--track" "--id-nth=1" "--expect=tab,btab,ctrl-r"
                     "--header" (slurp (cache (str "header." view))) "--header-first"
                     "--footer" (dim (if sub? "  type to filter · esc back" "  ⏎ select · tab next tab · type to filter · esc quit"))
                     "--preview" (str "cat " (cache) "/{1}.txt 2>/dev/null")
                     "--preview-window" (preview-window (wide?))
                     "--preview-label-pos=2"
                     (str "--listen=127.0.0.1:" port)
                     "--bind" (str "load:pos(" pos ")+unbind(load),change:first,"
                                   "focus:transform-preview-label(cat " (cache) "/{1}.hint 2>/dev/null),"
                                   "esc:transform(test -n \"$FZF_QUERY\" && echo clear-query || echo abort)")
                     ;; the cursor steps over the section headers (their ids start with h.)
                     "--bind" "down:down+transform(x={1}; test \"${x#h.}\" != \"$x\" && echo down)"
                     "--bind" "up:up+transform(x={1}; test \"${x#h.}\" != \"$x\" && echo up)")
              _ (swap! state assoc :showing nil)
              id (:id r)
              rw (some #(when (= id (safe (:id %))) %) (get-in @state [:views view]))]
          (cond
            (nil? r) (when sub? (recur "vm" nil))
            (#{"tab" "btab"} (:key r)) (let [i (.indexOf ^java.util.List tabs view)
                                             n (count tabs)]
                                         (recur (nth tabs (mod (+ (max i 0) (if (= "btab" (:key r)) (dec n) 1)) n)) nil))
            (= "ctrl-r" (:key r)) (do (reset! aws-seen nil) (refresh!) (recur view id))
            (or (nil? rw) (:header rw)) (recur view id)
            :else (let [res (binding [*beside* (cache (str (safe (:id rw)) ".txt"))]
                              (try (do-act! (:act rw))
                                   (catch Exception e (say! false (or (ex-message e) (str e))) {:did true})))]
                    ;; something was done: the new state before the list shows again (it is why you pressed enter)
                    (when (:did res)
                      (print "\033[?1049h\033[H\033[2J\033[?25l") (flush)
                      (try (refresh!) (catch Exception _)))
                    (.offer kick :now)
                    (if (:view res) (recur (:view res) nil) (recur view id))))))
      (catch Throwable e
        ;; never die behind the menu's screen: leave it, then say what happened
        (swap! state assoc :done true)
        (leave)
        (binding [*out* *err*] (println "bb vm stopped:" (or (ex-message e) (str e))))
        (System/exit 1))
      (finally (swap! state assoc :done true) (leave)))))
