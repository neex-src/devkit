(ns devkit.mono
  "The monorepo assembler. `bb mono:build` makes a FRESH git repo each run: devkit's main history,
  then every cleaned component merged in under its own folder.

  Per component: clone the cleaned repo, `git filter-repo --to-subdirectory-filter <name>` (history
  rewritten under the prefix, so `git log -- <name>/` and blame work; author/committer forced to
  neex <src@neex.dev>), fetch it into the monorepo and merge with one --no-ff merge commit whose
  dates are the component tip's date. Same inputs give the same shas. The monorepo's root README is
  resources/mono/README.md, added as the last commit. Inputs (the cleaned repos) are only read."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def components ["deliberator" "inliner" "reus" "ashamer" "sling"])
(def ident-name "neex")
(def ident-mail "src@neex.dev")
(def default-src "~/projects/greasr/.scratch/share/out")

(defn sh
  "Runs a command, returns trimmed stdout, throws with stderr when it fails."
  [{:keys [dir env]} & args]
  (let [r (apply p/sh {:dir (some-> dir str) :extra-env env} args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "failed: " (str/join " " args) "\n" (:err r)) {:exit (:exit r)})))
    (str/trim (:out r))))

(defn git [dir & args] (apply sh {:dir dir} "git" args))

(defn common-root
  "The devkit checkout that owns .scratch (the main one, also when run from a worktree)."
  []
  (let [c (sh {} "git" "rev-parse" "--path-format=absolute" "--git-common-dir")]
    {:git-dir c :root (str (fs/parent c))}))

(defn scratch [] (str (:root (common-root)) "/.scratch"))

(defn parse-opts [args]
  (loop [[a & more :as all] args o {}]
    (cond (empty? all) o
          (= a "--skip-missing") (recur more (assoc o :skip-missing true))
          (= a "--ignore-done") (recur more (assoc o :ignore-done true))
          (str/starts-with? a "--") (recur (rest more) (assoc o (keyword (subs a 2)) (first more)))
          :else (recur more (update o :args (fnil conj []) a)))))

(defn- generated? [dir] (fs/exists? (fs/path dir ".git" "devkit-mono-generated")))

(defn- fresh-dir! [dir]
  (when (fs/exists? dir)
    (when-not (or (generated? dir) (empty? (fs/list-dir dir)) (str/ends-with? (str dir) ".work"))
      (throw (ex-info (str dir " exists and was not made by mono:build; refusing to delete it") {})))
    (fs/delete-tree dir))
  (fs/create-dirs (fs/parent dir)))

(defn- ident-env [date]
  {"GIT_AUTHOR_NAME" ident-name "GIT_AUTHOR_EMAIL" ident-mail
   "GIT_COMMITTER_NAME" ident-name "GIT_COMMITTER_EMAIL" ident-mail
   "GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date
   "TMPDIR" (str (scratch) "/tmp")})

(defn- has-commits? [dir] (zero? (:exit (p/sh {:dir (str dir)} "git" "rev-parse" "--verify" "-q" "main^{commit}"))))

(defn- rewrite-component!
  "Clone src/<name> into work/<name> and move its whole history under <name>/."
  [src work name]
  (let [dst (str work "/" name)]
    (sh {} "git" "clone" "-q" "--no-local" "-b" "main" (str src "/" name) dst)
    (sh {:dir dst :env {"TMPDIR" (str (scratch) "/tmp")}}
        "git" "filter-repo" "--force" "--quiet" "--to-subdirectory-filter" name
        "--commit-callback"
        (str "commit.author_name=b'" ident-name "'; commit.author_email=b'" ident-mail "'; "
             "commit.committer_name=b'" ident-name "'; commit.committer_email=b'" ident-mail "'"))
    dst))

(defn- tip-date [dir] (git dir "log" "-1" "--format=%cd" "--date=raw" "main"))

(defn- epoch [raw] (parse-long (first (str/split raw #" "))))

(defn- ls-root [dir rev]
  (into {} (map (fn [l] (let [[_ _ sha n] (re-matches #"(\d+) \w+ (\w+)\t(.*)" l)] [n sha]))
                (str/split-lines (git dir "ls-tree" rev)))))

(defn- check [label ok? detail]
  (println (format "  %-5s %s%s" (if ok? "OK" "FAIL") label (if (seq detail) (str "  " detail) "")))
  ok?)

(defn build!
  "bb mono:build [--out DIR] [--src DIR] [--devkit-ref REF] [--skip-missing] [--ignore-done] [--components a,b]"
  [args]
  (let [o (parse-opts args)
        out (str (fs/absolutize (or (:out o) (str (scratch) "/mono/devkit"))))
        work (str out ".work")
        src (or (:src o) default-src)
        ref (or (:devkit-ref o) "main")
        {:keys [git-dir]} (common-root)
        readme (str (fs/absolutize (fs/path (:root (common-root)) "resources/mono/README.md")))
        readme (if (fs/exists? readme) readme
                   (str (fs/absolutize "resources/mono/README.md")))]
    (fresh-dir! out) (fresh-dir! work) (fs/create-dirs work)
    (fs/create-dirs (str (scratch) "/tmp"))
    ;; 1. devkit's own history, untouched (same shas as the devkit branch)
    (sh {} "git" "clone" "-q" "--no-local" "-b" ref git-dir out)
    (git out "remote" "remove" "origin")
    (spit (str out "/.git/devkit-mono-generated") "made by bb mono:build\n")
    (let [base (git out "rev-parse" "HEAD")
          wanted (if (:components o) (str/split (:components o) #",") components)
          present (filterv (fn [c]
                             (let [d (str src "/" c) done (str (fs/parent src) "/" c ".done")]
                               (cond (and (fs/exists? d) (has-commits? d)
                                          (or (:ignore-done o) (not (fs/exists? (fs/parent src))) (fs/exists? done)
                                              (not (some #(str/ends-with? (str %) ".done") (fs/list-dir (fs/parent src))))))
                                     true
                                     (:skip-missing o) (do (println "SKIPPED (no commits in" d ")") false)
                                     :else (throw (ex-info (str "component " c " has no commits or no " c ".done marker next to " src " (still being regenerated?) at " d
                                                                " (use --skip-missing to build without it)") {})))))
                           wanted)
          tips (into {} (for [c present] [c (git (str src "/" c) "rev-parse" "main")]))
          dates (into {} (for [c present] [c (tip-date (str src "/" c))]))]
      ;; 2. each component: rewrite under its prefix, merge --no-ff
      (doseq [c present]
        (let [w (rewrite-component! src work c)]
          (git out "fetch" "-q" w "main")
          (sh {:dir out :env (ident-env (dates c))}
              "git" "-c" "commit.gpgsign=false" "merge" "-q" "--no-ff" "--allow-unrelated-histories"
              "-m" (str "Merge " c " (cleaned history) into devkit\n\nSource tip: " (tips c)
                        "\nHistory rewritten under " c "/")
              "FETCH_HEAD")))
      ;; 3. root README
      (when (and (seq present) (fs/exists? readme))
        (fs/copy readme (str out "/README.md") {:replace-existing true})
        (git out "add" "README.md")
        (sh {:dir out :env (ident-env (apply max-key epoch (vals dates)))}
            "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "Root README of the devkit monorepo"))
      (fs/delete-tree work)
      ;; 4. checks
      (println "\nmono:build checks")
      (let [head (git out "rev-parse" "HEAD")
            real-tree-ok
            (for [c present]
              (let [mine (->> (str/split-lines (git out "ls-tree" "-r" (str "HEAD:" c)))
                              (map #(str/replace % #"\t" " ")) sort vec)
                    theirs (->> (str/split-lines (git (str src "/" c) "ls-tree" "-r" "main"))
                                (map #(str/replace % #"\t" " ")) sort vec)]
                (check (str c "/ == tip of the cleaned " c " (every path, mode, blob sha)")
                       (= mine theirs) (str (count mine) " files"))))
            counts-ok
            (for [c present]
              (let [m (git out "log" "-1" "--format=%H" "--merges" "--grep" (str "^Merge " c " ") "HEAD")
                    mine (parse-long (git out "rev-list" "--count" (str m "^2")))
                    theirs (parse-long (git (str src "/" c) "rev-list" "--count" "main"))
                    touching (parse-long (git out "rev-list" "--count" "HEAD" "--" (str c "/")))]
                (check (str c " commits: " mine " under the prefix + 1 merge, source has " theirs)
                       (= mine theirs) (str "git log -- " c "/ shows " touching))))
            main-root (ls-root git-dir ref)
            out-root (ls-root out "HEAD")
            expect-names (set (concat (keys main-root) present (when (seq present) ["README.md"])))
            root-ok (check "root files == devkit main (README.md = mono README)"
                           (= (dissoc main-root "README.md") (apply dissoc out-root "README.md" present)) "")
            stray-ok (check "no file outside the component folders + devkit's own"
                            (= (set (keys out-root)) expect-names)
                            (str "root: " (str/join " " (sort (keys out-root)))))
            idents (set (str/split-lines
                         (git out "log" "--format=%an <%ae>|%cn <%ce>" (str base "..HEAD"))))
            ident-ok (check "authors/committers of all new commits = neex <src@neex.dev>"
                            (every? #(= % "neex <src@neex.dev>|neex <src@neex.dev>") idents) (str idents))
            all (concat (doall real-tree-ok) (doall counts-ok) [root-ok stray-ok ident-ok])]
        (println "\nHEAD" head "in" out)
        (when-not (every? true? all)
          (println "CHECKS FAILED")
          (System/exit 1))
        (println "all checks passed")
        {:out out :head head}))))
