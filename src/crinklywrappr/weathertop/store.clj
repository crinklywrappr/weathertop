(ns crinklywrappr.weathertop.store)

;; --- State ---

(def ^:private call-state (atom {}))
;; keys: vectors of strings (call paths), values: long counts

(def ^:private event-queue
  (java.util.concurrent.LinkedBlockingQueue. 65536))
;; events: {:path ["ns/fn" "ns/fn2"], :thread-id 42}
;; .offer is non-blocking; dropped events under overload are acceptable

(def ^:private drainer-thread (atom nil))

;; --- Public API ---

(defn offer-event!
  "Enqueue a call event. Non-blocking; drops if queue is full.
   event-map keys: :path (required), :thread-id (required),
                   :dispatch-val (multimethods), :type (protocol fns)."
  [event-map]
  (.offer event-queue event-map))

(defn get-state
  "Return a snapshot of the current call-state map."
  []
  @call-state)

(defn reset-state!
  "Clear all recorded call counts."
  []
  (reset! call-state {}))

(defn- drain-and-update! []
  (let [event (.take event-queue)]          ; blocks until at least one arrives
    (when-not (:stop event)
      (let [batch (java.util.ArrayList.)]
        (.add batch event)
        (.drainTo event-queue batch)        ; collect remaining available events
        (swap! call-state
               (fn [state]
                 (reduce (fn [s e]
                           (if (:stop e)
                             (reduced s)    ; bail if poison pill is in batch
                             (update s (:path e) (fnil inc 0))))
                         state
                         batch))))
      true)))

(defn start-drainer!
  "Start the background drainer thread."
  []
  (let [t (Thread.
           (fn []
             (try
               (loop []
                 (when (drain-and-update!)
                   (recur)))
               (catch InterruptedException _
                 ;; Normal shutdown via interrupt
                 nil)))
           "weathertop-drainer")]
    (.setDaemon t true)
    (.start t)
    (reset! drainer-thread t)))

(defn stop-drainer!
  "Send a poison pill and join the drainer thread."
  []
  (when-let [t @drainer-thread]
    (.offer event-queue {:stop true})
    (.join t 5000)
    (reset! drainer-thread nil)))

;; --- Tree Builder ---

(defn- ensure-parents
  "If path [a b] exists but [a] doesn't, synthesize [a] with count 0."
  [state]
  (reduce (fn [s path]
            (reduce (fn [s' i]
                      (let [parent (subvec path 0 i)]
                        (if (contains? s' parent)
                          s'
                          (assoc s' parent 0))))
                    s
                    (range 1 (count path))))
          state
          (keys state)))

(defn- elem-fn  [e] (if (string? e) e (:fn e)))
(defn- elem-dv  [e] (when (map? e) (:dispatch-val e)))
(defn- elem-rt  [e] (when (map? e) (:record-type e)))
(defn- elem->id [e]
  (if-let [dv (or (elem-dv e) (elem-rt e))]
    (str (elem-fn e) "[" dv "]")
    (elem-fn e)))

(defn call-paths->tree
  "Convert the flat {path → count} map into the nested tree JSON structure.
   Path elements are plain strings or maps {:fn \"ns/fn\" :dispatch-val kw}."
  [state]
  (let [state     (ensure-parents state)
        all-paths (sort-by count (keys state))
        total     (reduce + 0 (vals state))
        max-depth 20]
    (letfn [(build-children [prefix]
              (->> all-paths
                   (filter (fn [p]
                             (and (= (count p) (inc (count prefix)))
                                  (= (take (count prefix) p) prefix)
                                  (<= (count p) max-depth))))
                   (map (fn [p]
                          (let [elem    (last p)
                                fq      (elem-fn elem)
                                cnt     (get state p 0)
                                id      (clojure.string/join "::" (map elem->id p))
                                parts   (clojure.string/split fq #"/")
                                fn-name (last parts)
                                ns-part (when (> (count parts) 1)
                                          (clojure.string/join "." (butlast parts)))]
                            (cond-> {:id         id
                                     :label      fq
                                     :fn_name    fn-name
                                     :call_count cnt
                                     :children   (build-children p)}
                              ns-part        (assoc :namespace    ns-part)
                              (elem-dv elem) (assoc :dispatch_val (str (elem-dv elem)))
                              (elem-rt elem) (assoc :record_type  (str (elem-rt elem)))))))
                   (sort-by :call_count >)))]
      {:schema_version 1
       :total_calls    total
       :root           {:id         "__root__"
                        :label      nil
                        :call_count 0
                        :children   (build-children [])}})))
