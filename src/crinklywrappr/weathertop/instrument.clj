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
         (not (:protocol m))
         (not (instance? clojure.lang.MultiFn @v))
         (not (::wrapped (meta @v))))))

(defn- fq-name
  "Returns the fully-qualified name string for a var."
  [v]
  (let [m (meta v)]
    (str (:ns m) "/" (:name m))))

(defn- make-wrapper
  "Wraps original-fn to record call events and maintain *call-stack*."
  [fq-sym original-fn]
  (let [wrapper (fn [& args]
                  (when (< (count *call-stack*) 20)
                    (store/offer-event! (conj *call-stack* fq-sym)
                                        (.getId (Thread/currentThread))))
                  (binding [*call-stack* (conj *call-stack* fq-sym)]
                    (apply original-fn args)))]
    (with-meta wrapper {::wrapped true})))

(defn instrument-ns!
  "Instruments all public, instrumentable functions in the given namespace."
  [ns-sym]
  (let [ns-obj (find-ns ns-sym)]
    (when ns-obj
      (doseq [[_sym v] (ns-publics ns-obj)]
        (when (instrumentable? v)
          (let [orig @v
                fq   (fq-name v)]
            (swap! originals assoc v orig)
            (alter-var-root v (constantly (make-wrapper fq orig)))))))))

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
