(ns crinklywrappr.weathertop.server
  (:require [crinklywrappr.weathertop.store :as store]
            [jsonista.core :as json]
            [org.httpkit.server :as httpkit]
            [ring.util.response :as response]))

;; --- SSE Client Registry ---

(def ^:private sse-clients (atom #{}))

(def ^:private broadcast-thread (atom nil))

;; --- Helpers ---

(defn- index-html []
  (-> (clojure.java.io/resource "crinklywrappr/weathertop/index.html")
      slurp))

(defn- json-response [data]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string data)})

;; --- Routes ---

(defn- handle-root [_req]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (index-html)})

(defn- handle-data [_req]
  (json-response (store/call-paths->tree (store/get-state))))

(defn- handle-events [req]
  (httpkit/as-channel req
    {:on-open  (fn [ch]
                 (swap! sse-clients conj ch)
                 (httpkit/send! ch
                   {:status  200
                    :headers {"Content-Type"  "text/event-stream"
                               "Cache-Control" "no-cache"
                               "Connection"    "keep-alive"}}
                   false))
     :on-close (fn [ch _]
                 (swap! sse-clients disj ch))}))

(defn- handle-404 [_req]
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body   "Not found"})

(defn router [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"]       (handle-root req)
    [:get "/data"]   (handle-data req)
    [:get "/events"] (handle-events req)
    (handle-404 req)))

;; --- Broadcast Loop ---

(defn- broadcast-state! []
  (let [tree (store/call-paths->tree (store/get-state))
        json (json/write-value-as-string tree)
        msg  (str "data: " json "\n\n")
        dead (atom #{})]
    (doseq [ch @sse-clients]
      (when-not (httpkit/send! ch msg false)
        (swap! dead conj ch)))
    (swap! sse-clients #(reduce disj % @dead))))

(defn- start-broadcast-thread! []
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (Thread/sleep 1000)
                 (broadcast-state!)
                 (recur))
               (catch InterruptedException _
                 nil)))
           "weathertop-broadcast")]
    (.setDaemon t true)
    (.start t)
    (reset! broadcast-thread t)))

(defn- stop-broadcast-thread! []
  (when-let [t @broadcast-thread]
    (.interrupt t)
    (.join t 3000)
    (reset! broadcast-thread nil)))

;; --- Server Lifecycle ---

(def ^:private server-handle (atom nil))

(defn start-server!
  "Start the http-kit server on the given port."
  [port]
  (let [srv (httpkit/run-server #'router {:port port :legacy-return-value? false})]
    (reset! server-handle srv))
  (start-broadcast-thread!))

(defn stop-server!
  "Stop the broadcast thread and close the http-kit server."
  []
  (stop-broadcast-thread!)
  (when-let [srv @server-handle]
    (httpkit/server-stop! srv)
    (reset! server-handle nil))
  (reset! sse-clients #{}))
