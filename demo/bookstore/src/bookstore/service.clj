(ns bookstore.service
  (:require [bookstore.db :as db]
            [bookstore.format :as fmt]
            [bookstore.validate :as validate])
  (:import [java.util UUID]))

(defn list-books [filters]
  (let [all    (db/all-books)
        books  (if-let [tag (:tag filters)]
                 (filter #(some #{tag} (:tags %)) all)
                 all)]
    (fmt/ok (fmt/books->wire books))))

(defn find-book [id]
  (if-let [book (db/find-book id)]
    (fmt/ok (fmt/book->wire book))
    (fmt/not-found (str "Book not found: " id))))

(defn create-book! [params]
  (let [result (validate/validate-book params)]
    (if (:ok result)
      (let [book (-> params
                     validate/coerce-book
                     (assoc :id (str (UUID/randomUUID))))]
        (fmt/created (fmt/book->wire (db/insert-book! book))))
      (fmt/bad-request (:errors result)))))

(defn update-book! [id params]
  (if-let [existing (db/find-book id)]
    (let [merged (merge existing params)
          result (validate/validate-book merged)]
      (if (:ok result)
        (let [coerced (validate/coerce-book params)
              updated (db/update-book! id coerced)]
          (fmt/ok (fmt/book->wire updated)))
        (fmt/bad-request (:errors result))))
    (fmt/not-found (str "Book not found: " id))))

(defn delete-book! [id]
  (if (db/remove-book! id)
    (fmt/no-content)
    (fmt/not-found (str "Book not found: " id))))

(defn- count-tag-overlap [source-tags book]
  (let [source-set (set source-tags)
        overlap    (count (filter source-set (:tags book)))]
    (assoc book :_overlap overlap)))

(defn- rank-by-overlap [books]
  (->> books
       (sort-by :_overlap >)
       (map #(dissoc % :_overlap))))

(defn similar-books [id]
  (if-let [source (db/find-book id)]
    (let [candidates  (db/books-by-tags (:tags source))
          others      (remove #(= (:id %) id) candidates)
          with-scores (map (partial count-tag-overlap (:tags source)) others)
          ranked      (rank-by-overlap with-scores)]
      (fmt/ok (fmt/books->wire ranked)))
    (fmt/not-found (str "Book not found: " id))))
