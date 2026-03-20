(ns bookstore.core
  (:require [bookstore.handler :as handler]
            [crinklywrappr.weathertop.core :as weathertop]
            [org.httpkit.server :as httpkit]))

(defn -main [& _args]
  (httpkit/run-server handler/app {:port 8080})
  (println "Bookstore running on http://localhost:8080")
  (weathertop/start! {:port 7777 :ns-prefixes ["bookstore"]})
  (println "Weathertop heatmap at http://localhost:7777"))
