(ns devkit.tui
  "`bb vm`: a small terminal menu for the dev VM. Plain babashka, nothing else: the terminal is put in
  raw mode with stty and keys are read from stdin.

  The state at the top is probed every 3 seconds (devkit.vm/probe), so it is true: the URL shows only
  when the page answers; a killed container shows as stopped; a stopped podman machine says so.
  Up/down (or k/j) move, enter runs, r refreshes, q or esc leaves. Every action is the function the
  plain `bb vm:*` task calls. Only one menu runs at a time (.scratch/tui.pid); a second one says so
  and exits. Leaving the menu, or closing its terminal, never touches the VM."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [devkit.vm :as vm]))

(def ^:private lock (str vm/root "/.scratch/tui.pid"))

(defn- alive? [pid]
  (-> (java.lang.ProcessHandle/of pid) (.map (reify java.util.function.Function (apply [_ h] (.isAlive ^java.lang.ProcessHandle h)))) (.orElse false)))

(defn- take-lock!
  "True when this process now owns the menu; false (with the other pid) when another menu is alive."
  []
  (let [other (some-> (when (fs/exists? lock) (slurp lock)) str/trim parse-long)]
    (if (and other (not= other (.pid (java.lang.ProcessHandle/current))) (alive? other))
      other
      (do (fs/create-dirs (fs/parent lock))
          (spit lock (str (.pid (java.lang.ProcessHandle/current))))
          nil))))

(defn- stty [& args]
  (-> (p/process {:in :inherit :out :string :err :string} "sh" "-c" (str "stty " (str/join " " args) " </dev/tty"))
      deref :out str/trim))

(defn- open-browser! []
  (let [cmd (if (re-find #"(?i)mac" (System/getProperty "os.name")) "open" "xdg-open")]
    (p/process cmd (vm/url))
    nil))

(defn- ask [prompt]
  (print prompt) (flush) (let [l (read-line)] (when l (str/trim l))))

(defn- start! []
  (let [s (vm/settings)
        w (ask (str "work folder on this machine [" (or (:work s) "podman volume devkit-work") "], enter = keep, - = the volume: "))
        r (ask (str "folder with the bare repos [" (or (:repos s) vm/default-repos) "], enter = keep: "))]
    (vm/up! (cond-> []
              (= w "-") (conj "--work" nil)
              (and (seq w) (not= w "-")) (conj "--work" w)
              (seq r) (conj "--repos" r)))))

(defn- guard
  "Wraps an action that needs the page to answer."
  [f what]
  (fn [st] (if (= :running (:state st)) (f) (println (str "not possible: " what ", the VM is " (:words st))))))

(def quiet
  "Actions with one line of output: they run inside the menu, no pause."
  #{"Stop the VM" "Open IntelliJ in the browser" "Start the podman machine"})

(def actions
  [["Start the VM (asks for folders)" (fn [_] (start!))]
   ["Stop the VM" (fn [_] (vm/down!))]
   ["Open IntelliJ in the browser" (guard open-browser! "no page to open")]
   ["Shell in the VM" (fn [st] (if (#{:running :starting :broken} (:state st)) (vm/shell!) (println "not possible: the VM is" (:words st))))]
   ["Logs" (fn [st] (if (#{:running :starting :broken} (:state st)) (vm/logs! []) (println "not possible: the VM is" (:words st))))]
   ["Build the image" (fn [_] (vm/build! []))]
   ["Checkout a repo into /work" (fn [_] (when-let [n (not-empty (ask "repository name (/repos/NAME.git): "))] (vm/checkout! n)))]
   ["Install corporate CAs (PEM)" (fn [_] (when-let [f (not-empty (ask "paths, separated by spaces: "))] (vm/ca-install! (str/split f #"\s+"))))]
   ["Start the podman machine" (fn [_] (vm/machine-start!))]
   ["Quit" nil]])

(defn- colour [state s]
  (let [c (case state :running "32" (:starting :stopped :not-built) "33" "31")]
    (str "\033[" c "m" s "\033[0m")))

(defn- render [st sel msg]
  (print "\033[H\033[J")
  (println "\033[1mdevkit VM\033[0m\r\n\r")
  (println (str "  state    " (colour (:state st) (str/upper-case (name (:state st)))) "  " (:words st) "\r"))
  (when (= :running (:state st)) (println (str "  url      " (vm/url) "\r")))
  (when (:units st) (println (str "  ide      " (get (:units st) "idea") ", display " (get (:units st) "xpra") "\r"))
    (println (str "  memory   " (:mem st) "\r")))
  (case (:state st)
    :machine-stopped (println "  next     choose: Start the podman machine\r")
    :not-built (println "  next     choose: Build the image\r")
    :stopped (println "  next     choose: Start the VM\r")
    :no-podman (println "  next     is podman installed and running?\r")
    nil)
  (println "\r")
  (doseq [[i [label _]] (map-indexed vector actions)]
    (println (str (if (= i sel) "\033[7m > " "   ") label (if (= i sel) " \033[0m" "") "\r")))
  (println "\r")
  (println "  arrows + enter, r refresh, q quit.\r")
  (when msg (println (str "  " (subs msg 0 (min 56 (count msg))) "\r")))
  (flush))

(defn- poll-key
  "A key if one is waiting (:up :down :enter :esc or a char), else nil after about 100 ms."
  []
  (if (pos? (.available System/in))
    (let [c (.read System/in)]
      (case c
        27 (if (pos? (.available System/in))
             (do (.read System/in) (case (.read System/in) 65 :up 66 :down :esc))
             :esc)
        (10 13) :enter
        -1 :esc
        (char c)))
    (do (Thread/sleep 100) nil)))

(defn- probe-now [] (try (vm/probe) (catch Exception e {:state :no-podman :words (ex-message e)})))

(defn run!
  "The menu loop."
  []
  (if-let [other (take-lock!)]
    (do (println (str "bb vm is already open in another terminal (pid " other "). Use that one, or close it first;"
                      " the plain `bb vm:status`, `bb vm:up`, `bb vm:down` work from here."))
        (System/exit 1))
    (let [saved (stty "-g")
          st (atom (probe-now))
          last-probe (atom (System/currentTimeMillis))
          restore (fn [] (stty saved) (fs/delete-if-exists lock))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable restore))
      (try
        (stty "-echo" "-icanon" "min" "0")
        (loop [sel 0 shown nil msg nil]
          (let [view [@st sel msg]]
            (when (not= view shown) (render @st sel msg))
            (let [k (poll-key)
                  now (System/currentTimeMillis)]
              (when (> (- now @last-probe) 3000)
                (reset! st (probe-now)) (reset! last-probe (System/currentTimeMillis)))
              (cond
                (#{:up \k} k) (recur (mod (dec sel) (count actions)) view nil)
                (#{:down \j} k) (recur (mod (inc sel) (count actions)) view nil)
                (= \r k) (do (reset! st (probe-now)) (recur sel nil nil))
                (#{:esc \q} k) nil
                (= k :enter)
                (let [[label f] (nth actions sel)]
                  (cond
                    (nil? f) nil
                    (quiet label)
                    (do (render @st sel (str label " ..."))
                        (let [o (with-out-str (try (f @st) (catch Exception e (println "failed:" (ex-message e)))))]
                          (reset! st (probe-now)) (reset! last-probe (System/currentTimeMillis))
                          (recur sel nil (last (remove str/blank? (str/split-lines o))))))
                    :else
                    (do (stty saved)
                        (print "\033[H\033[J") (flush)
                        (try (f @st) (catch Exception e (println "failed:" (ex-message e))))
                        (print "\n[enter] back to the menu ") (flush) (read-line)
                        (stty "-echo" "-icanon" "min" "0")
                        (reset! st (probe-now)) (reset! last-probe (System/currentTimeMillis))
                        (recur sel nil (str label ": done")))))
                :else (recur sel view msg)))))
        (finally (restore) (println))))))
