(ns crinklywrappr.stacklens.core
  (:require [clojure.string :as sg])
  (:import [java.lang StackWalker StackWalker$Option StackWalker$StackFrame]
           [java.util.function Function Predicate]
           [java.util.stream Collectors]
           [clojure.lang Compiler]))

(defrecord StackFrame [fn])

(def ^:private walker
  (StackWalker/getInstance StackWalker$Option/RETAIN_CLASS_REFERENCE))

;; --- Lazy method cache ---
;; {fn-class-name → {:fq-sym "ns/multi" :dispatch-val dv}}
;; Method body entries carry :dispatch-val; anonymous dispatch fn entries carry :dispatch-fn? true.
;; Protocol impl entries (extend-type / extend-protocol) carry :record-type.
;; nil means not yet built; any miss triggers a full rebuild.

(def ^:private method-cache (atom nil))
(def ^:private method-miss  (atom #{}))

(def original-multifn-key
  "Metadata key under which a wrapper fn may store the original MultiFn it
   replaced. When present, build-method-cache uses the stored MultiFn to
   index defmethod bodies rather than the wrapper."
  ::original-multifn)

(def original-method-class-key
  "Metadata key under which a method wrapper fn may store the original method
   body's class name. When present, build-method-cache uses it as the cache key
   instead of the wrapper's own class name, so cache rebuilds after instrumentation
   still map original-body class names to dispatch values."
  ::original-method-class)

(def ^:private dispatch-fn-field
  (doto (.getDeclaredField clojure.lang.MultiFn "dispatchFn")
    (.setAccessible true)))

(defn- anonymous-dispatch-fn? [^String cname]
  (let [[_ & segments] (sg/split cname #"\$")]
    (and (seq segments)
         (some #(.startsWith % "fn__") segments)
         (every? #(or (.startsWith % "fn__")
                      (.startsWith % "eval")) segments))))

(defn- build-method-cache [{:keys [include exclude]}]
  (let [filtered-nses
        (->> (all-ns)
             (filter (fn [nspace]
                       (let [nsname (.replace (str (ns-name nspace)) "-" "_")]
                         (and (or (nil? exclude)
                                  (not (some #(.startsWith nsname %) exclude)))
                              (or (nil? include)
                                  (some #(.startsWith nsname %) include)))))))
        all-vars (mapcat (fn [nspace] (vals (ns-interns nspace))) filtered-nses)]
    (->> (concat
          (->> all-vars
               (filter (fn [v]
                         (try (let [x @v]
                                (or (instance? clojure.lang.MultiFn x)
                                    (instance? clojure.lang.MultiFn
                                               (original-multifn-key (meta x)))))
                              (catch Exception _ false))))
               (mapcat (fn [v]
                         (let [m      (meta v)
                               fq     (str (ns-name (:ns m)) "/" (:name m))
                               x      @v
                               multi  (or (original-multifn-key (meta x)) x)
                               method-entries
                               (map (fn [[dv f]]
                                      [(or (original-method-class-key (meta f))
                                           (.getName (class f)))
                                       {:fq-sym fq :dispatch-val dv}])
                                    (methods multi))
                               dispatch-cname (.getName (class (.get dispatch-fn-field multi)))]
                           (cond-> method-entries
                             (anonymous-dispatch-fn? dispatch-cname)
                             (concat [[dispatch-cname {:fq-sym fq :dispatch-fn? true}]]))))))
          (->> all-vars
               (filter (fn [v]
                         (let [x @v]
                           (and (map? x)
                                (:sigs x)
                                (:on-interface x)
                                (map? (:impls x))))))
               (mapcat (fn [v]
                         (let [m        (meta v)
                               proto-ns (str (ns-name (:ns m)))
                               proto    @v]
                           (mapcat
                            (fn [[type-class method-map]]
                              (when type-class
                                (keep
                                 (fn [[method-key impl-fn]]
                                   (when impl-fn
                                     (let [cname (or (original-method-class-key (meta impl-fn))
                                                     (.getName (class impl-fn)))]
                                       (when (.contains cname "$")
                                         [cname {:fq-sym     (str proto-ns "/" (name method-key))
                                                 :record-type (.getSimpleName type-class)}]))))
                                 method-map)))
                            (:impls proto))))))
          (->> all-vars
               (filter (fn [v]
                         (let [vname (str (:name (meta v)))]
                           (and (or (.startsWith vname "->")
                                    (.startsWith vname "map->"))
                                (try (not (instance? clojure.lang.MultiFn @v))
                                     (catch Exception _ false))))))
               (map (fn [v]
                      (let [m      (meta v)
                            fq-sym (str (ns-name (:ns m)) "/" (:name m))]
                        [(.getName (class @v)) {:fq-sym fq-sym}])))))
         (into {}))))

(defn- lookup-defmethod [class-name opts]
  (cond
    (contains? @method-miss class-name) nil
    :else
    (or (get @method-cache class-name)
        (let [new-cache (build-method-cache opts)]
          (reset! method-cache new-cache)
          (if-let [result (get new-cache class-name)]
            result
            (do (swap! method-miss conj class-name)
                nil))))))

;; --- Frame predicate ---
;; Structural filter: include only defrecord/IRecord types, and Clojure fn
;; classes (containing "$"). Java stdlib has no "$" and doesn't implement
;; IRecord — excluded implicitly. clojure.lang.* has no "$" for most types —
;; excluded implicitly. Callers own all namespace-level decisions via
;; :include and :exclude.

(defn- make-pred [{:keys [include exclude]}]
  (reify Predicate
    (test [_ frame]
      (let [^Class cls (.getDeclaringClass frame)
            cname      (.getName cls)]
        (boolean
         (and (or (.isAssignableFrom clojure.lang.IRecord cls)
                  (.contains cname "$"))
              (or (nil? exclude)
                  (not (some #(.startsWith cname %) exclude)))
              (or (nil? include)
                  (some #(.startsWith cname %) include))))))))

;; --- Frame classification ---

;; Methods that defrecord adds from Object/ILookup/IPersistentMap/etc. — not
;; protocol methods.  Guards Case 1 against misclassifying infrastructure calls
;; (e.g. (:field record), (assoc record ...), (= r1 r2)) as protocol frames.
(def ^:private record-infra-methods
  #{"hashCode" "equals" "toString" "meta" "withMeta"
    "valAt" "assoc" "assocEx" "without" "containsKey" "entryAt"
    "count" "cons" "empty" "equiv" "seq" "iterator"
    "getBasis" "getField" "getLookupThunk"})

(defn- classify [^StackWalker$StackFrame frame opts]
  (let [^Class cls (.getDeclaringClass frame)
        cname      (.getName cls)
        mname      (.getMethodName frame)]
    (cond
      ;; Case 1: defrecord protocol / interface method.
      ;; IRecord is a marker interface on all defrecord types.
      ;; Skip infrastructure method names added by defrecord itself.
      (.isAssignableFrom clojure.lang.IRecord cls)
      (when-not (record-infra-methods mname)
        (map->StackFrame
         {:cname cls
          :fn (str (Compiler/demunge (.getPackageName cls))
                   "/"
                   (Compiler/demunge mname))
          :record-type (.getSimpleName cls)}))

      ;; Case 2: defmethod body, anonymous dispatch fn, or extend-type/extend-protocol impl.
      ;; Covers source-loaded (ns$eval123$fn__456), AOT-compiled (ns$fn__123)
      ;; method bodies, anonymous dispatch fns (ns$fn__NNN), and protocol impls.
      ;; Falls through to Case 3 on a miss.
      :else
      (if-let [{:keys [fq-sym dispatch-val dispatch-fn? record-type]} (lookup-defmethod cname opts)]
        (cond
          dispatch-fn? (map->StackFrame {:cname cls :fn fq-sym :multimethod-dispatch-fn? true})
          record-type  (map->StackFrame {:cname cls :fn fq-sym :record-type record-type})
          :else        (map->StackFrame {:cname cls :fn fq-sym :multimethod-dispatch-val dispatch-val}))
        ;; Case 3: named top-level Clojure fn (exactly one "$": ns$fn_name).
        ;; Multi-segment classes not in the method cache are runtime artifacts
        ;; (protocol dispatch wrappers, eval frames) — return nil to exclude them.
        (let [parts (sg/split cname #"\$")]
          (when (= 2 (count parts))
            (map->StackFrame {:cname cls :fn (Compiler/demunge cname)})))))))

;; --- Public API ---

(defn current-stack
  "Walk the current thread's JVM stack and return a list of StackFrame records,
   outermost frame first.

   opts is a map with optional keys:
     :include — seq of class-name prefixes; only frames with a matching prefix are kept
     :exclude — seq of class-name prefixes; frames with a matching prefix are dropped

   Both filters apply after the structural filter (IRecord types and $-containing
   class names). :exclude is applied before :include.

   Each StackFrame has:
     :fn                       — fully-qualified Clojure fn name (always present)
     :record-type              — simple class name for defrecord protocol methods; nil otherwise
     :multimethod-dispatch-val — dispatch value for defmethod bodies; nil otherwise
     :multimethod-dispatch-fn? — true for anonymous defmulti dispatch fn frames; nil otherwise"
  [opts]
  (.walk
   walker
   (reify Function
     (apply [_ stream]
       (transduce
        (keep #(classify % opts))
        (completing
         (fn [[acc last-cls] {:keys [cname] :as sf}]
           ;; deduplicate adjacent invokeStatic/invoke pairs
           (if (= cname last-cls)
             [acc last-cls]
             [(conj acc (dissoc sf :cname)) cname]))
         first)
        [(list) nil]
        (.collect (.filter stream (make-pred opts))
                  (Collectors/toList)))))))

(defn prime-cache!
  "Eagerly build the method cache with opts. Call this before instrumenting
   namespaces so defmethod bodies and dispatch fns are indexed while vars still
   hold their original MultiFn values."
  [opts]
  (reset! method-cache (build-method-cache opts))
  (reset! method-miss #{}))
