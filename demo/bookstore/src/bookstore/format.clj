(ns bookstore.format
  (:require [jsonista.core :as json]))

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/write-value-as-string body)})

(defn book->wire [book]
  (select-keys book [:id :title :author :tags :price :display_price]))

(defn books->wire [books]
  (mapv book->wire books))

(defn ok [body]
  (json-response 200 body))

(defn created [body]
  (json-response 201 body))

(defn no-content []
  {:status 204 :headers {} :body ""})

(defn not-found [message]
  (json-response 404 {:error message}))

(defn bad-request [errors]
  (json-response 400 {:errors errors}))
