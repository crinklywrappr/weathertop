(ns crinklywrappr.stacklens-test
  (:require [clojure.string :as sg]
            [clojure.test :refer [deftest is testing]]
            [crinklywrappr.stacklens.core :as stacklens])
  (:import [crinklywrappr.stacklens.core StackFrame]))

(def ^:private default-opts {:include ["crinklywrappr.stacklens_test"]})

;; --- Fixtures for test-regular-fn ---

(defn regular-inner [opts]
  (stacklens/current-stack opts))

(defn regular-outer [opts]
  (regular-inner opts))

;; --- Fixtures for test-defrecord-inline ---

(defprotocol TestProto
  (proto-method [this opts]))

(defrecord TestRecord []
  TestProto
  (proto-method [_ opts]
    (stacklens/current-stack opts)))

(defn record-outer [opts]
  (proto-method (->TestRecord) opts))

;; --- Fixtures for test-multimethod ---

(defmulti  test-multi (fn [x _opts] (:kind x)))
(defmethod test-multi :foo [_ opts]
  (stacklens/current-stack opts))

(defn multi-outer [opts]
  (test-multi {:kind :foo} opts))

;; --- Fixtures for test-multimethod-dispatch ---

(def ^:private !dispatch-stack (atom nil))

(defn- capture-stack! [opts]
  (reset! !dispatch-stack (stacklens/current-stack opts)))

(defmulti  test-multi-2 (fn [x opts] (capture-stack! opts) (:kind x)))
(defmethod test-multi-2 :bar [_ _] nil)

(defn multi-dispatch-outer [opts]
  (test-multi-2 {:kind :bar} opts))

;; --- Fixtures for test-extend-type ---

(defprotocol ExtendTypeProto
  (extend-type-method [this opts]))

(defrecord ExtendTypeRecord [])
(extend-type ExtendTypeRecord
  ExtendTypeProto
  (extend-type-method [_ opts]
    (stacklens/current-stack opts)))

(defn extend-type-outer [opts]
  (extend-type-method (->ExtendTypeRecord) opts))

;; --- Fixtures for test-extend-protocol ---

(defprotocol ExtendProtoProto
  (extend-proto-method [this opts]))

(defrecord ExtendProtoRecord [])
(extend-protocol ExtendProtoProto
  ExtendProtoRecord
  (extend-proto-method [_ opts]
    (stacklens/current-stack opts)))

(defn extend-proto-outer [opts]
  (extend-proto-method (->ExtendProtoRecord) opts))

;; --- Tests ---

(deftest test-regular-fn
  (testing "regular fns appear as StackFrames with :fn set and other fields nil"
    (let [stack (regular-outer default-opts)
          fns   (map :fn stack)]
      (is (every? #(instance? StackFrame %) stack))
      (is (every? #(nil? (:record-type %)) stack))
      (is (every? #(nil? (:multimethod-dispatch-val %)) stack))
      (is (some #(sg/ends-with? % "/regular-outer") fns))
      (is (some #(sg/ends-with? % "/regular-inner") fns))
      ;; outer appears before inner
      (let [outer-idx (.indexOf fns (first (filter #(sg/ends-with? % "/regular-outer") fns)))
            inner-idx (.indexOf fns (first (filter #(sg/ends-with? % "/regular-inner") fns)))]
        (is (< outer-idx inner-idx))))))

(deftest test-defrecord-inline
  (testing "inline defrecord protocol method appears with :record-type set"
    (let [stack        (record-outer default-opts)
          record-frame (first (filter #(= "TestRecord" (:record-type %)) stack))
          outer-frame  (first (filter #(sg/ends-with? (:fn %) "/record-outer") stack))]
      (is (some? record-frame))
      (is (sg/ends-with? (:fn record-frame) "/proto-method"))
      (is (nil? (:multimethod-dispatch-val record-frame)))
      (is (some? outer-frame))
      (is (nil? (:record-type outer-frame))))))

(deftest test-multimethod
  (testing "defmethod body appears with :multimethod-dispatch-val set"
    (let [stack       (multi-outer default-opts)
          multi-frame (first (filter #(= :foo (:multimethod-dispatch-val %)) stack))
          outer-frame (first (filter #(sg/ends-with? (:fn %) "/multi-outer") stack))]
      (is (some? multi-frame))
      (is (sg/ends-with? (:fn multi-frame) "/test-multi"))
      (is (nil? (:record-type multi-frame)))
      (is (some? outer-frame))
      (is (nil? (:multimethod-dispatch-val outer-frame))))))

(deftest test-multimethod-dispatch-fn
  (testing "stack inside defmulti dispatch fn has :multimethod-dispatch-fn? true"
    (multi-dispatch-outer default-opts)
    (let [stack @!dispatch-stack
          fns   (map :fn stack)]
      (is (every? #(instance? StackFrame %) stack))
      (is (some  #(true? (:multimethod-dispatch-fn? %)) stack))
      (is (some #(sg/ends-with? % "/capture-stack!") fns))
      (is (some #(sg/ends-with? % "/multi-dispatch-outer") fns))
      (is (some #(sg/ends-with? % "/test-multi-2") fns))
      (let [outer-idx (.indexOf fns (first (filter #(sg/ends-with? % "/multi-dispatch-outer") fns)))
            cap-idx   (.indexOf fns (first (filter #(sg/ends-with? % "/capture-stack!") fns)))]
        (is (< outer-idx cap-idx))))))

(deftest test-extend-type
  (testing "extend-type protocol method appears with :record-type set"
    (let [stack       (extend-type-outer default-opts)
          impl-frame  (first (filter #(= "ExtendTypeRecord" (:record-type %)) stack))
          outer-frame (first (filter #(sg/ends-with? (:fn %) "/extend-type-outer") stack))]
      (is (some? impl-frame))
      (is (sg/ends-with? (:fn impl-frame) "/extend-type-method"))
      (is (nil? (:multimethod-dispatch-val impl-frame)))
      (is (some? outer-frame))
      (is (nil? (:record-type outer-frame))))))

(deftest test-extend-protocol
  (testing "extend-protocol method appears with :record-type set"
    (let [stack       (extend-proto-outer default-opts)
          impl-frame  (first (filter #(= "ExtendProtoRecord" (:record-type %)) stack))
          outer-frame (first (filter #(sg/ends-with? (:fn %) "/extend-proto-outer") stack))]
      (is (some? impl-frame))
      (is (sg/ends-with? (:fn impl-frame) "/extend-proto-method"))
      (is (nil? (:multimethod-dispatch-val impl-frame)))
      (is (some? outer-frame))
      (is (nil? (:record-type outer-frame))))))

(deftest test-exclude
  (testing ":exclude removes frames matching the given prefixes"
    (let [stack (stacklens/current-stack
                  {:include ["crinklywrappr.stacklens_test"]
                   :exclude ["crinklywrappr.stacklens_test$regular"]})]
      (is (not-any? #(sg/starts-with? (:fn %) "crinklywrappr.stacklens-test/regular")
                    stack)))))

(deftest test-include
  (testing ":include restricts to frames matching the given prefixes"
    (let [stack (regular-outer {:include ["crinklywrappr.stacklens_test"]})]
      (is (every? #(sg/starts-with? (:fn %) "crinklywrappr.stacklens-test")
                  stack)))))
