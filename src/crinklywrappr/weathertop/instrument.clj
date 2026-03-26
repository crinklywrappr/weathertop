(ns crinklywrappr.weathertop.instrument
  (:require [crinklywrappr.weathertop.store :as store]
            [crinklywrappr.stacklens.core :as stacklens])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [org.objectweb.asm ClassReader ClassWriter ClassVisitor MethodVisitor Opcodes]
           [java.lang.instrument ClassFileTransformer Instrumentation]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def ^:dynamic *collecting?* false)
;; Re-entrancy guard: prevents instrumented fns called from within
;; stacklens/current-stack from recursively triggering another stack walk.

(def ^:private active-opts        (atom nil))   ; stacklens opts map
(def ^:private active-ns-syms     (atom #{}))   ; #{ns-sym ...}
(def ^:private active-transformer (atom nil))   ; ClassFileTransformer
(def ^:private instrumentation    (atom nil))   ; java.lang.instrument.Instrumentation

;; ---------------------------------------------------------------------------
;; Path helpers (carried over from prototype)
;; ---------------------------------------------------------------------------

(defn- frame->path-elem [sf]
  (cond-> {:fn (:fn sf)}
    (:multimethod-dispatch-val sf) (assoc :dispatch-val (:multimethod-dispatch-val sf))
    (:record-type sf)              (assoc :record-type (:record-type sf))))

(defn- expand-multimethods [callers]
  ;; Insert a generic copy before each multimethod variant frame so the path always
  ;; has a named intermediate node (e.g. search/search-books) between the caller and
  ;; the dispatch-val variant. The multimethod dispatch fn executes and returns before
  ;; the body runs, so it is never on the JVM stack — this expansion is the only way
  ;; to get the intermediate into the path.
  ;; Protocol/IRecord variants (:record-type) are intentionally NOT expanded: their
  ;; callers already capture the aggregate count via their own hook events.
  (reduce (fn [acc elem]
            (if (:dispatch-val elem)
              (conj acc {:fn (:fn elem)} elem)
              (conj acc elem)))
          []
          callers))

;; ---------------------------------------------------------------------------
;; Hook — called from injected bytecode at each instrumented method entry
;; ---------------------------------------------------------------------------

(defn -on-method-enter []
  (when (and @active-opts (not *collecting?*))
    (binding [*collecting?* true]
      (let [stack (stacklens/current-stack @active-opts)
            tid   (.getId (Thread/currentThread))]
        (when-not (:multimethod-dispatch-fn? (last stack))
          (let [path (expand-multimethods (mapv frame->path-elem stack))]
            (when (seq path)
              (let [last-elem (last path)]
                ;; For multimethod variants: expand-multimethods has already made the
                ;; path 3+ elements, so butlast points to the intermediate node (not
                ;; the caller). Record it to give the intermediate the correct count.
                ;; Protocol/IRecord variants are not expanded; butlast would be
                ;; [caller], double-counting the caller's own hook event — skip it.
                (when (:dispatch-val last-elem)
                  (store/offer-event! {:path (vec (butlast path)) :thread-id tid}))
                (store/offer-event! {:path path :thread-id tid})))))))))

;; ---------------------------------------------------------------------------
;; ASM bytecode injection
;; ---------------------------------------------------------------------------

;; Injected at the top of each target invoke/method:
;;   LDC  "crinklywrappr.weathertop.instrument"
;;   LDC  "-on-method-enter"
;;   INVOKESTATIC  clojure/lang/RT.var → Var
;;   INVOKEVIRTUAL clojure/lang/Var.invoke → Object
;;   POP

(def ^:private hook-ns  "crinklywrappr.weathertop.instrument")
(def ^:private hook-var "-on-method-enter")

(defn- injecting-method-visitor [^MethodVisitor mv]
  (proxy [MethodVisitor] [Opcodes/ASM9 mv]
    (visitCode []
      (proxy-super visitCode)
      (.visitLdcInsn this hook-ns)
      (.visitLdcInsn this hook-var)
      (.visitMethodInsn this
        Opcodes/INVOKESTATIC
        "clojure/lang/RT"
        "var"
        "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;"
        false)
      (.visitMethodInsn this
        Opcodes/INVOKEVIRTUAL
        "clojure/lang/Var"
        "invoke"
        "()Ljava/lang/Object;"
        false)
      (.visitInsn this Opcodes/POP))))

;; ---------------------------------------------------------------------------
;; Target-class matching helpers
;; ---------------------------------------------------------------------------

(def ^:private record-infra-methods
  #{"hashCode" "equals" "toString" "meta" "withMeta"
    "valAt" "assoc" "assocEx" "without" "containsKey" "entryAt"
    "count" "cons" "empty" "equiv" "seq" "iterator"
    "getBasis" "getField"})

(defn- irecord-class? [^Class cls]
  (.isAssignableFrom clojure.lang.IRecord cls))

(defn- ns-prefix-for [ns-sym]
  ;; "my.ns-name" → "my/ns_name"  (JVM internal slash form, munged)
  (-> (str ns-sym)
      (.replace "." "/")
      (.replace "-" "_")))

(defn- dollar-segments [^String class-name]
  (count (filter #(= \$ %) class-name)))

(defn- should-instrument-class?
  "Returns true when class-name/cls should be transformed.
   ns-prefixes    — set of munged JVM-internal prefix strings  (e.g. #{\"my/ns\"})
   cached-classes — set of class names present in stacklens method cache"
  [^String class-name ^Class cls ns-prefixes cached-classes]
  (let [jvm-name (when class-name (.replace class-name "." "/"))]
    (cond
      ;; IRecord: instrument if package prefix matches
      (and cls (irecord-class? cls))
      (some #(.startsWith (.replace (.getName cls) "." "/") %) ns-prefixes)

      ;; Clojure fn class (contains "$")
      (and jvm-name (.contains jvm-name "$"))
      (and (some #(.startsWith jvm-name %) ns-prefixes)
           (or (= 1 (dollar-segments class-name))         ; ns$fn_name
               (contains? cached-classes class-name)))    ; defmethod body

      :else false)))

;; ---------------------------------------------------------------------------
;; ClassFileTransformer
;; ---------------------------------------------------------------------------

(defn- has-invoke-static?
  "Returns true if the class bytecode contains an invokeStatic method."
  [^bytes class-bytes]
  (try
    (let [cr    (ClassReader. class-bytes)
          found (atom false)]
      (.accept cr
               (proxy [ClassVisitor] [Opcodes/ASM9]
                 (visitMethod [_access ^String mname _desc _sig _exs]
                   (when (= mname "invokeStatic") (reset! found true))
                   nil))
               ClassReader/SKIP_CODE)
      @found)
    (catch Throwable _ false)))

(defn- make-class-visitor
  "Returns a ClassVisitor that injects the hook into relevant methods.
   hook-static? — true  → hook invokeStatic (named fn, has static body)
                  false → hook invoke       (anonymous closure fn, no invokeStatic)"
  [^ClassWriter cw is-irecord hook-static?]
  (proxy [ClassVisitor] [Opcodes/ASM9 cw]
    (visitMethod [access ^String mname ^String desc ^String signature exceptions]
      (let [mv (.visitMethod cw access mname desc signature exceptions)]
        (cond
          ;; IRecord: inject into all non-infrastructure, non-constructor methods
          (and is-irecord
               (not (contains? record-infra-methods mname))
               (not= "<init>" mname))
          (injecting-method-visitor mv)

          ;; Named fn class: hook invokeStatic only (invoke delegates to it).
          ;; Anonymous closure fn: hook invoke (no invokeStatic exists).
          (and (not is-irecord)
               (= mname (if hook-static? "invokeStatic" "invoke")))
          (injecting-method-visitor mv)

          :else mv)))))

;; ---------------------------------------------------------------------------
;; Agent installation
;; ---------------------------------------------------------------------------

(defn- install-agent! []
  (when (nil? @instrumentation)
    (reset! instrumentation (ByteBuddyAgent/install))))

;; ---------------------------------------------------------------------------
;; Method-cache access (stacklens keeps it private; ns-interns gives access)
;; ---------------------------------------------------------------------------

(defn- method-cache-keys []
  (when-let [v (get (ns-interns 'crinklywrappr.stacklens.core) 'method-cache)]
    (set (keys @@v))))

;; ---------------------------------------------------------------------------
;; Retransform helpers
;; ---------------------------------------------------------------------------

(defn- target-loaded-classes
  "Returns all currently-loaded classes that match ns-prefixes / cached-classes."
  [^Instrumentation inst ns-prefixes cached-classes]
  (->> (.getAllLoadedClasses inst)
       (filter #(should-instrument-class? (.getName %) % ns-prefixes cached-classes))))

(defn- retransform! [^Instrumentation inst classes]
  (when (seq classes)
    (try
      (.retransformClasses inst (into-array Class classes))
      (catch Throwable t
        (println "retransformClasses error:" (.getMessage t))))))

;; ---------------------------------------------------------------------------
;; Transformer lifecycle
;; ---------------------------------------------------------------------------

;; Shared atoms read by the transformer on every transform call.
;; Updated before retransforming new classes, so new classes see the latest prefixes.
(def ^:private t-ns-prefixes  (atom #{}))
(def ^:private t-cached-cls   (atom #{}))

(defn- already-instrumented?
  "Returns true if class-bytes already contain the hook-ns constant,
   meaning the hook has been injected by a previous retransform."
  [^bytes class-bytes]
  (try
    ;; Scan bytes for the UTF-8 hook-ns string in the constant pool.
    ;; This is intentionally a raw byte scan — ClassReader does not expose cp strings.
    (let [hook-bytes (.getBytes hook-ns "UTF-8")
          hl         (alength hook-bytes)
          cl         (alength class-bytes)]
      (loop [i 0]
        (cond
          (> (+ i hl) cl) false
          (loop [j 0]
            (if (= j hl)
              true
              (if (= (aget class-bytes (+ i j)) (aget hook-bytes j))
                (recur (inc j))
                false)))
          true
          :else (recur (inc i)))))
    (catch Throwable _ false)))

(defn- make-live-transformer
  "Returns a transformer that reads ns-prefixes and cached-classes from shared atoms.
   Idempotent: skips classes that already carry the hook constant."
  []
  (reify ClassFileTransformer
    (transform [_ _loader class-name cls _pd class-bytes]
      (let [np @t-ns-prefixes
            cc @t-cached-cls]
        (when (and class-bytes (seq np)
                   (not (already-instrumented? class-bytes))
                   (should-instrument-class?
                     (when class-name (.replace class-name "/" "."))
                     cls np cc))
          (try
            (let [cr         (ClassReader. class-bytes)
                  cw         (ClassWriter. cr ClassWriter/COMPUTE_MAXS)
                  is-irecord (and cls (irecord-class? cls))
                  hook-static? (and (not is-irecord) (has-invoke-static? class-bytes))
                  cv         (make-class-visitor cw is-irecord hook-static?)]
              (.accept cr cv 0)
              (.toByteArray cw))
            (catch Throwable _ nil)))))))

(defn- ensure-transformer!
  "Registers the live transformer once; subsequent calls are no-ops."
  []
  (when (nil? @active-transformer)
    (let [t (make-live-transformer)]
      (.addTransformer @instrumentation t true)
      (reset! active-transformer t))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn instrument-ns!
  "Instruments all fns/methods in ns-sym via JVM bytecode transformation.

   stacklens-opts — passed to stacklens/current-stack. Typically {:include [...]}.
   Defaults to {} (no filtering)."
  ([ns-sym] (instrument-ns! ns-sym {}))
  ([ns-sym stacklens-opts]
   (install-agent!)
   (reset! active-opts stacklens-opts)
   (when (not (contains? @active-ns-syms ns-sym))
     (stacklens/prime-cache! stacklens-opts)
     (let [np (conj (set (map ns-prefix-for @active-ns-syms)) (ns-prefix-for ns-sym))
           cc (method-cache-keys)]
       (swap! active-ns-syms conj ns-sym)
       ;; Register the live transformer once; it reads t-ns-prefixes / t-cached-cls
       (ensure-transformer!)
       ;; Expose the updated prefixes to the transformer, then retransform new ns's classes only
       (reset! t-ns-prefixes np)
       (reset! t-cached-cls cc)
       (retransform! @instrumentation
                     (target-loaded-classes @instrumentation
                                           (set [(ns-prefix-for ns-sym)])
                                           cc))))))

(defn uninstrument-all!
  "Disables instrumentation. Hook bytecode remains but is gated on active-opts,
   so hooks are harmless after this call."
  []
  ;; Disable the hook gate first so any in-flight calls see nil opts immediately
  (reset! active-opts nil)
  (reset! active-ns-syms #{})
  ;; Remove the transformer so future class loads are not instrumented.
  ;; We do NOT attempt bytecode revert: retransformClasses uses last-transformed
  ;; bytes as the base, so removing and retransforming would re-apply — not revert.
  ;; Remaining hook calls see active-opts = nil and return immediately.
  (when-let [inst @instrumentation]
    (when-let [t @active-transformer]
      (.removeTransformer inst t)
      (reset! active-transformer nil)
      (reset! t-ns-prefixes #{})
      (reset! t-cached-cls #{}))))

(defn instrumented-namespaces
  "Returns the set of namespace symbols currently being instrumented."
  []
  @active-ns-syms)
