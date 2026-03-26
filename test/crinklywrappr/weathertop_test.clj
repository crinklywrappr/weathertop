(ns crinklywrappr.weathertop-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [crinklywrappr.weathertop.store :as store]
            [crinklywrappr.weathertop.instrument :as instrument]))

;; --- Fixtures ---

(defn reset-store-fixture [f]
  (store/reset-state!)
  (f)
  (store/reset-state!))

(use-fixtures :each reset-store-fixture)

;; --- Protocol test fixtures ---

(defprotocol TestPricer
  (test-price [this amount]))

;; Case 1: inline defrecord — the key new case (was invisible with alter-var-root)
(defrecord InlineTestPrice []
  TestPricer
  (test-price [_ amount] amount))

;; Case 2: extend-type
(defrecord ExtendTestPrice [])
(extend-type ExtendTestPrice
  TestPricer
  (test-price [_ amount] amount))

(defn test-caller [pricer x]
  (test-price pricer x))

;; --- Multimethod test fixtures ---

(defmulti  test-pricer-multi (fn [x _] (:kind x)))
(defmethod test-pricer-multi :flat [_ amount] amount)
(defmethod test-pricer-multi :half [_ amount] (/ amount 2.0))

(defn multi-caller [kind amount]
  (test-pricer-multi {:kind kind} amount))

;; --- Store tests ---

(deftest test-offer-and-drain
  (testing "events are accumulated in call-state after draining"
    (store/start-drainer!)
    (store/offer-event! {:path ["myapp.core/handler"] :thread-id 1})
    (store/offer-event! {:path ["myapp.core/handler"] :thread-id 1})
    (store/offer-event! {:path ["myapp.core/handler" "myapp.db/query"] :thread-id 2})
    (Thread/sleep 200)
    (let [state (store/get-state)]
      (is (= 2 (get state ["myapp.core/handler"])))
      (is (= 1 (get state ["myapp.core/handler" "myapp.db/query"]))))
    (store/stop-drainer!)))

(deftest test-reset-state
  (testing "reset-state! clears all counts"
    (store/start-drainer!)
    (store/offer-event! {:path ["ns/fn"] :thread-id 1})
    (Thread/sleep 150)
    (store/reset-state!)
    (is (= {} (store/get-state)))
    (store/stop-drainer!)))

(deftest test-call-paths->tree-empty
  (testing "empty state produces root with no children"
    (let [tree (store/call-paths->tree {})]
      (is (= 1 (:schema_version tree)))
      (is (= 0 (:total_calls tree)))
      (is (= "__root__" (get-in tree [:root :id])))
      (is (empty? (get-in tree [:root :children]))))))

(deftest test-call-paths->tree-structure
  (testing "single path produces correct tree"
    (let [state {["myapp.core/handler"] 5}
          tree  (store/call-paths->tree state)
          child (first (get-in tree [:root :children]))]
      (is (= 5 (:total_calls tree)))
      (is (some? child))
      (is (= "myapp.core/handler" (:id child)))
      (is (= "myapp.core/handler" (:label child)))
      (is (= 5 (:call_count child))))))

(deftest test-call-paths->tree-nested
  (testing "nested paths produce correct parent/child structure"
    (let [state {["ns/a"]          3
                 ["ns/a" "ns/b"]   2}
          tree  (store/call-paths->tree state)
          root-children (get-in tree [:root :children])
          parent (first root-children)
          child  (first (:children parent))]
      (is (= 1 (count root-children)))
      (is (= "ns/a" (:id parent)))
      (is (= 3 (:call_count parent)))
      (is (= "ns/a::ns/b" (:id child)))
      (is (= 2 (:call_count child))))))

(deftest test-call-paths->tree-synthesizes-parents
  (testing "missing parent paths are synthesized with count 0"
    (let [state {["ns/a" "ns/b"] 4}
          tree  (store/call-paths->tree state)
          root-children (get-in tree [:root :children])
          parent (first root-children)]
      (is (= 1 (count root-children)))
      (is (= "ns/a" (:id parent)))
      (is (= 0 (:call_count parent)))
      (is (= 1 (count (:children parent)))))))

(deftest test-total-calls
  (testing "total_calls sums all values in state (including synthesized parents)"
    (let [state {["a"] 10 ["a" "b"] 5}
          tree  (store/call-paths->tree state)]
      (is (= 15 (:total_calls tree))))))

;; --- Instrumentation tests ---

(def ^:private test-opts {:include ["crinklywrappr.weathertop_test"]})

(deftest test-instrument-and-uninstrument
  (testing "instrument-ns! adds ns; uninstrument-all! clears it"
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (is (contains? (instrument/instrumented-namespaces) 'crinklywrappr.weathertop-test))
    ;; Instrumented fn still works correctly
    (is (= 42 (test-caller (->InlineTestPrice) 42)))
    (instrument/uninstrument-all!)
    (is (empty? (instrument/instrumented-namespaces)))))

(deftest test-call-recorded
  (testing "calling an instrumented fn records an event in the store"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (test-caller (->InlineTestPrice) 42)
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= "crinklywrappr.weathertop-test/test-caller" (:fn (last %))) paths)
          "test-caller should be recorded"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-no-double-wrap
  (testing "instrumenting the same ns twice does not double-count events"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (test-caller (->InlineTestPrice) 1)
    (Thread/sleep 200)
    (let [state        (store/get-state)
          caller-paths (filter #(= "crinklywrappr.weathertop-test/test-caller"
                                   (:fn (last %))) (keys state))]
      (is (= 1 (reduce + (map state caller-paths)))
          "test-caller should be counted exactly once"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-inline-defrecord-recorded
  (testing "inline defrecord protocol method is recorded (IRecord path)"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (test-caller (->InlineTestPrice) 99)
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= "InlineTestPrice" (:record-type (last %))) paths)
          "InlineTestPrice variant frame should appear in recorded paths"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-extend-type-recorded
  (testing "extend-type protocol fn records caller and variant"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (test-caller (->ExtendTestPrice) 50)
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= "ExtendTestPrice" (:record-type (last %))) paths)
          "ExtendTestPrice variant frame should appear in recorded paths"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-multimethod-recorded
  (testing "defmethod body records caller + generic + dispatch-val variant"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (multi-caller :flat 100)
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= :flat (:dispatch-val (last %))) paths)
          ":flat dispatch-val variant should appear in recorded paths"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-constructor-recorded
  (testing "->RecordName constructor fn call is recorded"
    (store/start-drainer!)
    (instrument/instrument-ns! 'crinklywrappr.weathertop-test test-opts)
    (->InlineTestPrice)
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= "crinklywrappr.weathertop-test/->InlineTestPrice"
                    (:fn (last %)))
                paths)
          "->InlineTestPrice constructor should be recorded"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))
