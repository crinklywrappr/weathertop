(ns crinklywrappr.weathertop.core
  (:require [crinklywrappr.weathertop.instrument :as instrument]
            [crinklywrappr.weathertop.server :as server]
            [crinklywrappr.weathertop.store :as store]
            [crinklywrappr.stacklens.core :as stacklens]))

(defn start!
  "Instruments namespaces and starts the web server.

  opts:
    :ns-prefixes - required vector of namespace prefix strings to instrument,
                   e.g. [\"myapp\"] or [\"myapp\" \"mylib\"]
    :port        - HTTP port (default: 7777)"
  [& [opts]]
  (let [{:keys [ns-prefixes port]
         :or   {port 7777}} opts]
    (when (empty? ns-prefixes)
      (throw (ex-info ":ns-prefixes is required and must be a non-empty vector of strings"
                      {:opts opts})))
    (let [stacklens-opts {:include (mapv #(clojure.string/replace % "-" "_") ns-prefixes)}
          nses (->> (all-ns)
                    (map ns-name)
                    (filter (fn [ns-sym]
                              (let [n (name ns-sym)]
                                (some #(clojure.string/starts-with? n %) ns-prefixes)))))]
      (instrument/uninstrument-all!)
      (store/stop-drainer!)
      (server/stop-server!)
      (stacklens/prime-cache! stacklens-opts)
      (store/start-drainer!)
      (doseq [ns-sym nses]
        (instrument/instrument-ns! ns-sym stacklens-opts))
      (server/start-server! port)
      (println (str "Weathertop started on http://localhost:" port
                    " — instrumenting: " (vec (instrument/instrumented-namespaces)))))))

(defn stop!
  "Uninstruments all functions and stops the web server."
  []
  (instrument/uninstrument-all!)
  (store/stop-drainer!)
  (server/stop-server!)
  (println "Weathertop stopped."))
