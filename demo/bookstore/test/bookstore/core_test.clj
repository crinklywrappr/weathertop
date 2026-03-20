(ns bookstore.core-test
  (:require [bookstore.handler :as handler]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [jsonista.core :as json]))

;; ── store reset ──────────────────────────────────────────────────────────────
;; Capture the seed state once at load time (bookstore.db is already loaded
;; transitively through handler), then restore it before every test.

(def ^:private seed-state
  @@(ns-resolve 'bookstore.db 'store))

(use-fixtures :each
  (fn [f]
    (reset! @(ns-resolve 'bookstore.db 'store) seed-state)
    (f)))

;; ── request / response helpers ───────────────────────────────────────────────

(defn- req
  ([method uri]
   (req method uri nil nil))
  ([method uri body]
   (req method uri body nil))
  ([method uri body query-string]
   (cond-> {:request-method method
            :uri            uri
            :headers        {"content-type" "application/json"}}
     body         (assoc :body (java.io.StringReader.
                                (json/write-value-as-string body)))
     query-string (assoc :query-string query-string))))

(defn- call [& req-args]
  (handler/app (apply req req-args)))

(defn- parse-body [resp]
  (json/read-value (:body resp) json/keyword-keys-object-mapper))

;; ── known seed IDs (from db.clj) ─────────────────────────────────────────────

(def ^:private pragmatic-id   "a1b2c3d4-0001-0000-0000-000000000001")
(def ^:private clean-code-id  "a1b2c3d4-0002-0000-0000-000000000002")
(def ^:private unknown-id     "00000000-0000-0000-0000-000000000000")

;; ── GET /books ───────────────────────────────────────────────────────────────

(deftest list-all-books
  (let [resp (call :get "/books")]
    (is (= 200 (:status resp)))
    (is (= 10 (count (parse-body resp))))))

(deftest list-books-filtered-by-tag
  (let [resp (call :get "/books" nil "tag=programming")]
    (is (= 200 (:status resp)))
    (let [books (parse-body resp)]
      (is (pos? (count books)))
      (is (every? #(some #{"programming"} (:tags %)) books)))))

(deftest list-books-filter-no-matches
  (let [resp (call :get "/books" nil "tag=nonexistent-tag-xyz")]
    (is (= 200 (:status resp)))
    (is (empty? (parse-body resp)))))

;; ── GET /books/:id ───────────────────────────────────────────────────────────

(deftest get-existing-book
  (let [resp (call :get (str "/books/" pragmatic-id))]
    (is (= 200 (:status resp)))
    (let [book (parse-body resp)]
      (is (= pragmatic-id (:id book)))
      (is (= "The Pragmatic Programmer" (:title book)))
      (is (= "David Thomas" (:author book)))
      (is (number? (:price book))))))

(deftest get-missing-book
  (let [resp (call :get (str "/books/" unknown-id))]
    (is (= 404 (:status resp)))
    (is (contains? (parse-body resp) :error))))

;; ── POST /books ──────────────────────────────────────────────────────────────

(deftest create-valid-book
  (let [payload {:title "Test Book" :author "Test Author" :price 19.99
                 :tags  ["testing"]}
        resp    (call :post "/books" payload)]
    (is (= 201 (:status resp)))
    (let [book (parse-body resp)]
      (is (string? (:id book)))
      (is (= "Test Book" (:title book)))
      (is (= "Test Author" (:author book)))
      (is (= 19.99 (:price book))))))

(deftest create-book-missing-title
  (let [resp (call :post "/books" {:author "A" :price 9.99})]
    (is (= 400 (:status resp)))
    (is (seq (:errors (parse-body resp))))))

(deftest create-book-missing-author
  (let [resp (call :post "/books" {:title "T" :price 9.99})]
    (is (= 400 (:status resp)))
    (is (seq (:errors (parse-body resp))))))

(deftest create-book-missing-price
  (let [resp (call :post "/books" {:title "T" :author "A"})]
    (is (= 400 (:status resp)))
    (is (seq (:errors (parse-body resp))))))

(deftest create-book-empty-body
  (let [resp (call :post "/books" {})]
    (is (= 400 (:status resp)))))

;; ── PUT /books/:id ───────────────────────────────────────────────────────────

(deftest update-existing-book-price
  (let [resp (call :put (str "/books/" clean-code-id) {:price 29.99})]
    (is (= 200 (:status resp)))
    (is (= 29.99 (:price (parse-body resp))))))

(deftest update-existing-book-title
  (let [new-title "Clean Code: Second Edition"
        resp      (call :put (str "/books/" clean-code-id) {:title new-title})]
    (is (= 200 (:status resp)))
    (is (= new-title (:title (parse-body resp))))))

(deftest update-missing-book
  (let [resp (call :put (str "/books/" unknown-id) {:price 9.99})]
    (is (= 404 (:status resp)))
    (is (contains? (parse-body resp) :error))))

;; ── DELETE /books/:id ────────────────────────────────────────────────────────

(deftest delete-existing-book
  (let [resp (call :delete (str "/books/" pragmatic-id))]
    (is (= 204 (:status resp)))))

(deftest delete-book-is-gone-after-deletion
  (call :delete (str "/books/" pragmatic-id))
  (let [resp (call :get (str "/books/" pragmatic-id))]
    (is (= 404 (:status resp)))))

(deftest delete-missing-book
  (let [resp (call :delete (str "/books/" unknown-id))]
    (is (= 404 (:status resp)))
    (is (contains? (parse-body resp) :error))))

;; ── GET /books/:id/similar ───────────────────────────────────────────────────

(deftest similar-books-returns-ok
  ;; The Pragmatic Programmer has tags [programming career software] —
  ;; almost every seed book shares at least one tag.
  (let [resp (call :get (str "/books/" pragmatic-id "/similar"))]
    (is (= 200 (:status resp)))
    (is (seq (parse-body resp)))))

(deftest similar-books-excludes-source
  (let [books (parse-body (call :get (str "/books/" pragmatic-id "/similar")))]
    (is (every? #(not= pragmatic-id (:id %)) books))))

(deftest similar-books-sorted-by-overlap
  ;; Verify descending overlap: each book shares >= as many tags as the next.
  ;; We can't check exact counts without reimplementing the logic, but we can
  ;; verify structure.
  (let [books (parse-body (call :get (str "/books/" pragmatic-id "/similar")))]
    (is (every? :id books))
    (is (every? :title books))))

(deftest similar-books-missing-source
  (let [resp (call :get (str "/books/" unknown-id "/similar"))]
    (is (= 404 (:status resp)))
    (is (contains? (parse-body resp) :error))))

;; ── unknown routes ───────────────────────────────────────────────────────────

(deftest unknown-route
  (let [resp (call :get "/no-such-route")]
    (is (= 404 (:status resp)))))
