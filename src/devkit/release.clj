(ns devkit.release
  "The two IntelliJ plugin zips (deliberator, inliner), built from the cleaned copies inside the
  generated monorepo, never from the private repos.

  `bb release:collect` opens tmux window `devkit-release` that runs `bb release:run`: a fresh clone of
  the generated monorepo (.scratch/release/src, so the monorepo itself stays pristine), then ONE Gradle
  build after the other. Log: .scratch/logs/release.log. Output: .scratch/artifacts/ = the zips +
  manifest.edn (the contract the VM build reads: see `manifest`). `bb release:publish` only prints
  the gh commands."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [devkit.mono :as mono])
  (:import (java.security MessageDigest)
           (java.util.zip ZipFile)))

(def plugins
  "name, build dir inside the monorepo, build command, where the zip lands"
  [{:name "deliberator" :dir "deliberator" :cmd ["bb" "ide:build"] :dist "deliberator/ide/build/distributions"}
   {:name "inliner" :dir "inliner" :cmd ["./gradlew" "buildPlugin"] :dist "inliner/build/distributions"}])

(defn- paths []
  (let [s (mono/scratch)]
    {:scratch s :artifacts (str s "/artifacts") :log (str s "/logs/release.log")
     :done (str s "/logs/release.done") :src (str s "/release/src")
     :repo (str s "/mono/devkit") :tmp (str s "/tmp")}))

(defn- sha256 [f]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (fs/read-all-bytes f)) (map #(format "%02x" %)) (apply str))))

(defn- plugin-xml
  "plugin.xml out of the built zip: <zip>/<dir>/lib/*.jar -> META-INF/plugin.xml."
  [zip]
  (with-open [z (ZipFile. (str zip))]
    (some (fn [e]
            (when (re-matches #".*/lib/[^/]+\.jar" (.getName e))
              (let [bytes (with-open [in (.getInputStream z e)] (.readAllBytes in))
                    jar (fs/create-temp-file {:dir (:tmp (paths)) :suffix ".jar"})]
                (try (fs/write-bytes jar bytes)
                     (with-open [j (ZipFile. (str jar))]
                       (when-let [x (.getEntry j "META-INF/plugin.xml")]
                         (slurp (.getInputStream j x))))
                     (finally (fs/delete jar))))))
          (enumeration-seq (.entries z)))))

(defn- attr [xml tag a]
  (second (re-find (re-pattern (str "<" tag "\\b[^>]*\\b" a "=\"([^\"]*)\"")) xml)))
(defn- text [xml tag] (second (re-find (re-pattern (str "<" tag "\\b[^>]*>([^<]*)</" tag ">")) xml)))

(defn- run-logged [dir log env cmd]
  (println "\n>>>" (str/join " " cmd) "in" dir)
  (spit log (str "\n>>> " (str/join " " cmd) " in " dir "\n") :append true)
  (let [r (apply p/shell {:dir dir :extra-env env :continue true}
                   (into ["bash" "-c" "set -o pipefail; log=$1; shift; \"$@\" 2>&1 | tee -a \"$log\"" "_" log] cmd))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "FAILED (" (:exit r) "): " (str/join " " cmd)) {})))))

(defn run!
  "Inside the tmux window: fresh clone of the monorepo, the builds, artifacts + manifest.edn."
  [args]
  (let [o (mono/parse-opts args)
        {:keys [artifacts log done src tmp repo]} (paths)
        repo (or (:repo o) repo)
        env {"TMPDIR" tmp}]
    (fs/create-dirs (fs/parent log)) (fs/create-dirs tmp)
    (spit log (str "release:run " (java.util.Date.) " repo " repo "\n"))
    (fs/delete-if-exists done)
    (try
      (when-not (fs/exists? (str repo "/.git")) (throw (ex-info (str "no monorepo at " repo ": run bb mono:build first") {})))
      (when (fs/exists? src) (fs/delete-tree src))
      (fs/create-dirs (fs/parent src))
      (mono/sh {} "git" "clone" "-q" "--no-local" repo src)
      (let [head (mono/git src "rev-parse" "HEAD")]
        (fs/create-dirs artifacts)
        (doseq [f (fs/list-dir artifacts)] (fs/delete-tree f))
        (let [entries
              (vec (for [{:keys [name dir cmd dist]} plugins
                           :when (or (nil? (:only o)) (contains? (set (str/split (:only o) #",")) name))]
                     (do (run-logged (str src "/" dir) log env cmd)
                         (let [zips (vec (fs/glob (str src "/" dist) "*.zip"))
                               _ (when-not (= 1 (count zips)) (throw (ex-info (str name ": expected one zip in " dist ", found " (count zips)) {})))
                               zip (first zips)
                               xml (or (plugin-xml zip) (throw (ex-info (str name ": no plugin.xml in " zip) {})))
                               out (str artifacts "/" (fs/file-name zip))]
                           (fs/copy zip out)
                           (array-map :name name
                                       :file (str (fs/file-name zip))
                                       :plugin-id (text xml "id")
                                       :version (text xml "version")
                                       :sha256 (sha256 out)
                                       :size (fs/size out)
                                       :source-sha head
                                       :source-tree (mono/git src "rev-parse" (str "HEAD:" dir))
                                       :since-build (attr xml "idea-version" "since-build")
                                       :until-build (attr xml "idea-version" "until-build")
                                       :vendor-email (attr xml "vendor" "email"))))))]
          (spit (str artifacts "/manifest.edn")
                (with-out-str (pp/pprint (array-map :format 1 :built-at (str (java.time.Instant/now)) :source-sha head :plugins entries))))
          (println "\nmanifest:\n" (slurp (str artifacts "/manifest.edn")))
          (spit done "0\n")))
      (catch Throwable t
        (println "RELEASE FAILED:" (ex-message t))
        (spit log (str "RELEASE FAILED: " (ex-message t) "\n") :append true)
        (spit done "1\n")
        (System/exit 1)))))

(defn collect!
  "bb release:collect [--repo DIR] [--detach]: starts window devkit-release, waits for it."
  [args]
  (let [o (mono/parse-opts args)
        {:keys [done log artifacts]} (paths)
        cwd (str (fs/cwd))
        cmd (str "bb release:run"
                  (when (:repo o) (str " --repo " (fs/absolutize (:repo o))))
                  (when (:only o) (str " --only " (:only o))))
        in-tmux? (some? (System/getenv "TMUX"))
        exists? (zero? (:exit (p/sh "tmux" "has-session" "-t" "devkit-release")))]
    (fs/create-dirs (fs/parent log))
    (fs/delete-if-exists done)
    (if in-tmux?
      (do (p/sh "tmux" "kill-window" "-t" "devkit-release")
          (mono/sh {} "tmux" "new-window" "-d" "-n" "devkit-release" "-c" cwd cmd)
          (p/sh "tmux" "set-option" "-w" "-t" "devkit-release" "remain-on-exit" "on"))
      (do (when exists? (p/sh "tmux" "kill-session" "-t" "devkit-release"))
          (mono/sh {} "tmux" "new-session" "-d" "-s" "devkit-release" "-n" "devkit-release" "-c" cwd cmd)))
    (println "started tmux window devkit-release; log" log)
    (when-not (:detach o)
      (while (not (fs/exists? done)) (Thread/sleep 5000))
      (let [ok? (= "0" (str/trim (slurp done)))]
        (println (if ok? "DONE" "FAILED") "- see" log)
        (when ok? (println (slurp (str artifacts "/manifest.edn"))))
        (System/exit (if ok? 0 1))))))

(defn publish!
  "bb release:publish --org ORG [--name REPO] [--tag TAG]: prints the gh commands, runs nothing."
  [args]
  (let [o (mono/parse-opts args)
        {:keys [artifacts]} (paths)
        org (or (:org o) (throw (ex-info "usage: bb release:publish --org ORG [--name REPO] [--tag TAG]" {})))
        repo (or (:name o) "devkit")
        mf (edn/read-string (slurp (str artifacts "/manifest.edn")))
        tag (or (:tag o) (str "plugins-" (subs (:source-sha mf) 0 8)))
        files (map #(str artifacts "/" (:file %)) (:plugins mf))]
    (println "# NOT RUN. Copy and run them yourself.")
    (println "gh release create" tag "--repo" (str org "/" repo)
             "--title" (pr-str (str "IDE plugins " tag))
             "--notes" (pr-str (str "Built from monorepo commit " (:source-sha mf) ". "
                                    (str/join ", " (map #(str (:name %) " " (:version %) " (IDE builds " (:since-build %) " to " (:until-build %) ")") (:plugins mf)))))
             (str/join " " (concat files [(str artifacts "/manifest.edn")])))
    (println "\n# a later release of fresher zips: gh release upload TAG FILES --clobber --repo" (str org "/" repo))))
