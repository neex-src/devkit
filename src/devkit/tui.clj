(ns devkit.tui
  "`bb vm`: a small terminal menu for the dev VM. Plain babashka, no fzf or other tool:
  the terminal is put in raw mode with stty, the keys are read from stdin.
  Up/down (or k/j) move, enter runs, q or esc leaves, r refreshes the state.
  Every action is the same function the plain `bb vm:*` task calls."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [devkit.vm :as vm]))

(defn- stty [& args]
  (-> (p/process {:in :inherit :out :string :err :string} "sh" "-c" (str "stty " (str/join " " args) " </dev/tty"))
      deref :out str/trim))

(defn state
  "What the header shows: built?, running?, url, memory."
  []
  (try
    (let [built (boolean (vm/out "podman" "image" "exists" vm/image))
          up (and (vm/out "podman" "info") (vm/running?))]
      {:built built :running (boolean up) :url (vm/url)
       :mem (when up (vm/out "podman" "stats" "--no-stream" "--format" "{{.MemUsage}}" vm/container))
       :ide (when up (vm/out "podman" "exec" vm/container "systemctl" "is-active" "idea"))})
    (catch Exception e {:error (ex-message e)})))

(defn- open-browser! []
  (let [cmd (if (re-find #"(?i)mac" (System/getProperty "os.name")) "open" "xdg-open")]
    (p/process cmd (vm/url))
    nil))

(defn- ask [prompt]
  (print prompt) (flush) (read-line))

(def actions
  [["Build the image" #(vm/build! [])]
   ["Start the VM" #(vm/up! [])]
   ["Stop the VM" #(vm/down!)]
   ["Shell in the VM" #(vm/shell!)]
   ["Logs" #(vm/logs! [])]
   ["Open in the browser" open-browser!]
   ["Install a corporate CA (PEM file)" #(when-let [f (not-empty (ask "path to the .pem file: "))] (vm/ca-install! [f]))]
   ["Quit" nil]])

(defn- render [s sel msg]
  (print "\033[2J\033[H")
  (println "\033[1mdevkit VM\033[0m\r")
  (println "\r")
  (if (:error s)
    (println (str "  podman: " (:error s) "\r"))
    (do (println (str "  image    " (if (:built s) "\033[32mbuilt\033[0m" "\033[33mnot built\033[0m") "\r"))
        (println (str "  vm       " (if (:running s) "\033[32mRUNNING\033[0m" "stopped") "\r"))
        (when (:running s)
          (println (str "  url      " (:url s) "\r"))
          (println (str "  ide      " (:ide s) "\r"))
          (println (str "  memory   " (:mem s) "\r")))))
  (println "\r")
  (doseq [[i [label _]] (map-indexed vector actions)]
    (println (str (if (= i sel) "\033[7m > " "   ") label (if (= i sel) " \033[0m" "") "\r")))
  (println "\r")
  (println (str "  arrows/enter, r refresh, q quit  " (or msg "") "\r"))
  (flush))

(defn- read-key
  "One key: :up :down :enter :esc, or the character."
  []
  (let [c (.read System/in)]
    (case c
      27 (if (pos? (.available System/in))
           (do (.read System/in) (case (.read System/in) 65 :up 66 :down :esc))
           :esc)
      (10 13) :enter
      -1 :esc
      (char c))))

(defn run!
  "The menu loop."
  []
  (let [saved (stty "-g")]
    (try
      (loop [sel 0 s (state) msg nil]
        (stty "-echo" "-icanon" "min" "1")
        (render s sel msg)
        (let [k (read-key)]
          (cond
            (#{:up \k} k) (recur (mod (dec sel) (count actions)) s nil)
            (#{:down \j} k) (recur (mod (inc sel) (count actions)) s nil)
            (#{\r} k) (recur sel (state) nil)
            (#{:esc \q} k) nil
            (= k :enter)
            (let [[label f] (nth actions sel)]
              (when f
                (stty saved)
                (print "\033[2J\033[H") (flush)
                (let [err (try (f) nil (catch Exception e (ex-message e)))]
                  (when err (println "failed:" err))
                  (print "\n[enter] back to the menu ") (flush) (read-line)
                  (recur sel (state) (str label " done"))))
              )
            :else (recur sel s nil))))
      (finally (stty saved) (println)))))
