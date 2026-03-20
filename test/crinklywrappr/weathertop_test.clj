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

;; --- Store tests ---

(deftest test-offer-and-drain
  (testing "events are accumulated in call-state after draining"
    (store/start-drainer!)
    (store/offer-event! ["myapp.core/handler"] 1)
    (store/offer-event! ["myapp.core/handler"] 1)
    (store/offer-event! ["myapp.core/handler" "myapp.db/query"] 2)
    (Thread/sleep 200)                         ; let drainer process
    (let [state (store/get-state)]
      (is (= 2 (get state ["myapp.core/handler"])))
      (is (= 1 (get state ["myapp.core/handler" "myapp.db/query"]))))
    (store/stop-drainer!)))

(deftest test-reset-state
  (testing "reset-state! clears all counts"
    (store/start-drainer!)
    (store/offer-event! ["ns/fn"] 1)
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
    (let [state {["ns/a"]       3
                 ["ns/a" "ns/b"] 2}
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

(deftest test-instrument-and-uninstrument
  (testing "instrument-ns! wraps public fns; uninstrument-all! restores them"
    (let [orig clojure.string/upper-case]
      (instrument/instrument-ns! 'clojure.string)
      (is (contains? (instrument/instrumented-namespaces) 'clojure.string))
      ;; Instrumented fn still works
      (is (= "HELLO" (clojure.string/upper-case "hello")))
      (instrument/uninstrument-all!)
      (is (empty? (instrument/instrumented-namespaces)))
      ;; Var restored to original
      (is (= orig clojure.string/upper-case)))))

(deftest test-call-recorded
  (testing "calling an instrumented fn records an event in the store"
    (store/start-drainer!)
    (instrument/instrument-ns! 'clojure.string)
    (clojure.string/split "a,b,c" #",")
    (Thread/sleep 200)
    (let [state (store/get-state)
          paths (keys state)]
      (is (some #(= "clojure.string/split" (last %)) paths)))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))

(deftest test-no-double-wrap
  (testing "instrumenting the same ns twice does not double-wrap"
    (instrument/instrument-ns! 'clojure.string)
    (let [wrapped clojure.string/upper-case]
      (instrument/instrument-ns! 'clojure.string)   ; second call
      ;; Still the same wrapper object (no re-wrap)
      (is (= wrapped clojure.string/upper-case)))
    (instrument/uninstrument-all!)))

(deftest test-call-stack-threading
  (testing "*call-stack* propagates depth within a single thread"
    (store/start-drainer!)
    (instrument/instrument-ns! 'clojure.string)
    ;; Manually binding to simulate an outer context
    (binding [instrument/*call-stack* ["outer/fn"]]
      (clojure.string/upper-case "x"))
    (Thread/sleep 200)
    (let [state (store/get-state)
          deep  (filter #(= 2 (count %)) (keys state))]
      (is (seq deep) "Should have at least one depth-2 path"))
    (instrument/uninstrument-all!)
    (store/stop-drainer!)))
