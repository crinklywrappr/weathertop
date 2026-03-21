(ns crinklywrappr.weathertop.instrument
  (:require [crinklywrappr.weathertop.store :as store]))

(def ^:dynamic *call-stack*
  "Thread-local call stack. Does NOT cross thread boundaries.
   Calls in futures, pmap, or threadpools will appear as top-level entries.
   core.async go blocks lose call stack context at park points."
  [])

(def ^:private originals (atom {}))
;; {Var → original-fn}  used for uninstrumentation

(defn- instrumentable?
  "Returns true if the var should be instrumented."
  [v]
  (let [m (meta v)]
    (and (ifn? @v)
         (not (:macro m))
         (not (::wrapped (meta @v))))))

(defn- fq-name
  "Returns the fully-qualified name string for a var."
  [v]
  (let [m (meta v)]
    (str (:ns m) "/" (:name m))))

(defn- make-wrapper
  "Wraps original-fn to record call events and maintain *call-stack*.
   opts: :protocol? — when true, captures the class of the first arg as :type."
  [fq-sym original-fn {:keys [protocol?]}]
  (let [wrapper (fn [& args]
                  (when (< (count *call-stack*) 20)
                    (let [type-name (when protocol?
                                      (some-> args first class .getSimpleName))]
                      (store/offer-event!
                        (cond-> {:path      (conj *call-stack* fq-sym)
                                 :thread-id (.getId (Thread/currentThread))}
                          type-name (assoc :type type-name)))))
                  (binding [*call-stack* (conj *call-stack* fq-sym)]
                    (apply original-fn args)))]
    (with-meta wrapper {::wrapped true})))

(defn- multimethod-dispatch-fn
  "Extracts the dispatch function from a MultiFn via reflection."
  [^clojure.lang.MultiFn mf]
  (let [f (doto (.getDeclaredField clojure.lang.MultiFn "dispatchFn")
            (.setAccessible true))]
    (.get f mf)))

(defn- make-multimethod-wrapper
  "Wraps a MultiFn to record call events including the dispatch value."
  [fq-sym ^clojure.lang.MultiFn original-mf]
  (let [dispatch-fn (multimethod-dispatch-fn original-mf)
        wrapper     (fn [& args]
                      (when (< (count *call-stack*) 20)
                        (let [dval (try (apply dispatch-fn args)
                                        (catch Exception _ nil))]
                          (store/offer-event!
                            (cond-> {:path      (conj *call-stack* fq-sym)
                                     :thread-id (.getId (Thread/currentThread))}
                              (some? dval) (assoc :dispatch-val dval)))))
                      (binding [*call-stack* (conj *call-stack* fq-sym)]
                        (apply original-mf args)))]
    (with-meta wrapper {::wrapped true})))

(defn instrument-ns!
  "Instruments all instrumentable functions in the given namespace,
   including private functions, multimethods, and protocol functions."
  [ns-sym]
  (let [ns-obj (find-ns ns-sym)]
    (when ns-obj
      (doseq [[_sym v] (ns-interns ns-obj)]
        (when (instrumentable? v)
          (let [orig @v
                fq   (fq-name v)]
            (swap! originals assoc v orig)
            (alter-var-root v (constantly
                               (if (instance? clojure.lang.MultiFn orig)
                                 (make-multimethod-wrapper fq orig)
                                 (make-wrapper fq orig {:protocol? (boolean (:protocol (meta v)))}))))))))))

(defn uninstrument-all!
  "Restores all instrumented vars to their original functions."
  []
  (doseq [[v orig] @originals]
    (alter-var-root v (constantly orig)))
  (reset! originals {}))

(defn instrumented-namespaces
  "Returns a set of namespace symbols currently being instrumented."
  []
  (->> (keys @originals)
       (map #(ns-name (:ns (meta %))))
       set))
