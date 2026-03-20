(ns bookstore.handler
  (:require [bookstore.service :as service]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [jsonista.core :as json]
            [ring.middleware.params :refer [wrap-params]]))

(defn- parse-body [req]
  (when-let [body (:body req)]
    (try
      (json/read-value (slurp body) json/keyword-keys-object-mapper)
      (catch Exception _ nil))))

(defn handle-list [req]
  (let [params (:query-params req)
        tag    (get params "tag")]
    (service/list-books (cond-> {} tag (assoc :tag tag)))))

(defn handle-create [req]
  (service/create-book! (or (parse-body req) {})))

(defn handle-get [id]
  (service/find-book id))

(defn handle-update [req]
  (let [id (get-in req [:route-params :id])]
    (service/update-book! id (or (parse-body req) {}))))

(defn handle-delete [id]
  (service/delete-book! id))

(defn handle-similar [id]
  (service/similar-books id))

(defroutes app-routes
  (GET    "/books"              req  (handle-list req))
  (POST   "/books"              req  (handle-create req))
  (GET    "/books/:id/similar"  [id] (handle-similar id))
  (GET    "/books/:id"          [id] (handle-get id))
  (PUT    "/books/:id"          req  (handle-update req))
  (DELETE "/books/:id"          [id] (handle-delete id))
  (route/not-found "{\"error\":\"not found\"}"))

(def app (wrap-params app-routes))
