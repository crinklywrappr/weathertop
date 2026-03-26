(ns bookstore.search
  (:require [bookstore.db :as db]))

(defn by-query [query]
  (:by query))

;; written this way to test correct rendering of multimethods
(defmulti search-books
  "Search books by the strategy named in (:by query).
   Dispatch values: :title, :author, :price-range."
  (fn [query] (by-query query)))

(defmethod search-books :title [{:keys [q]}]
  (let [q-lower (clojure.string/lower-case (or q ""))]
    (filter #(clojure.string/includes?
               (clojure.string/lower-case (:title %))
               q-lower)
            (db/all-books))))

(defmethod search-books :author [{:keys [q]}]
  (let [q-lower (clojure.string/lower-case (or q ""))]
    (filter #(clojure.string/includes?
               (clojure.string/lower-case (:author %))
               q-lower)
            (db/all-books))))

(defmethod search-books :price-range [{:keys [min max]}]
  (let [lo (or min 0.0)
        hi (or max Double/MAX_VALUE)]
    (filter #(and (>= (:price %) lo)
                  (<= (:price %) hi))
            (db/all-books))))

(defmethod search-books :default [query]
  (throw (ex-info "Unknown search strategy" {:by (:by query)})))
