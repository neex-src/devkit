(ns ca-proof
  "bb test/ca_proof.bb PROXY_URL_FROM_MACHINE PROXY_URL_FROM_VM [with|without]
  A TLS-intercepting proxy (mitmproxy) is running; try an HTTPS fetch through it from curl,
  from the system JDK, from IntelliJ's JBR and an image pull in the podman machine.
  Prints PASS/FAIL per line. 'without' expects every fetch to fail, 'with' expects success."
  (:require [babashka.process :as p] [clojure.string :as str]))
(def container "devkit")
(defn vm [cmd] (p/shell {:out :string :err :string :continue true} "podman" "exec" "-u" "dev" container "bash" "-lc" cmd))
(def java-src "import java.net.*;import java.net.http.*;public class Fetch{public static void main(String[] a)throws Exception{var u=URI.create(a[0]);var pr=URI.create(a[1]);var c=HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress(pr.getHost(),pr.getPort()))).build();var r=c.send(HttpRequest.newBuilder(u).build(),HttpResponse.BodyHandlers.discarding());System.out.println(\"HTTP \"+r.statusCode());}}")
(defn -main [mproxy vproxy mode]
  (let [want (= mode "with")
        results (atom [])
        check (fn [label r]
                (let [ok (zero? (:exit r)) pass (= ok want)]
                  (swap! results conj pass)
                  (println (if pass "PASS" "FAIL") label "-> exit" (:exit r) (str/trim (str (:out r) (when-not ok (str " " (last (str/split-lines (str (:err r) (:out r))))))))))) ]
    (vm (str "mkdir -p ~/proof && cat > ~/proof/Fetch.java <<'EOF'\n" java-src "\nEOF\ncd ~/proof && javac Fetch.java"))
    (println "mode" mode)
    (check "curl" (vm (str "curl -sS -m 25 -x " vproxy " -o /dev/null -w 'HTTP %{http_code}' https://example.com")))
    (check "java system JDK" (vm (str "cd ~/proof && java -cp . Fetch https://example.com " vproxy)))
    (check "java IntelliJ JBR" (vm (str "cd ~/proof && /opt/idea/jbr/bin/java -cp . Fetch https://example.com " vproxy)))
    (check "git" (vm (str "git -c http.proxy=" vproxy " ls-remote https://github.com/babashka/babashka HEAD")))
    (check "terraform init (Go, system store)" (vm (str "mkdir -p ~/proof/tf && cd ~/proof/tf && printf 'terraform {\\n required_providers {\\n null = { source = \"hashicorp/null\" }\\n }\\n}\\n' > main.tf && rm -rf .terraform && HTTPS_PROXY=" vproxy " terraform init -no-color")))
    (p/shell {:continue true :out :string :err :string} "podman" "rmi" "-f" "docker.io/library/hello-world:latest")
    (check "podman machine image pull" (p/shell {:out :string :err :string :continue true} "podman" "machine" "ssh"
                                                (str "HTTPS_PROXY=" mproxy " HTTP_PROXY=" mproxy " podman pull docker.io/library/hello-world:latest")))
    (System/exit (if (every? true? @results) 0 1))))
(apply -main *command-line-args*)
