(ns build
  (:require [clojure.tools.build.api :as b]))

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn test [_]
  (let [result (b/process {:command-args ["clojure" "-M:test"
                                          "-m" "cognitect.test-runner"
                                          "--dir" "test"]})]
    (when (not= 0 (:exit result))
      (throw (ex-info "Tests failed" result)))))

(defn run [_]
  (b/process {:command-args ["clojure" "-M:run"]}))
