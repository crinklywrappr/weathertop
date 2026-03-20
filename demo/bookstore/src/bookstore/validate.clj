(ns bookstore.validate)

(defn validate-book [params]
  (let [errors (cond-> []
                 (not (seq (:title params)))
                 (conj "title is required")

                 (not (seq (:author params)))
                 (conj "author is required")

                 (nil? (:price params))
                 (conj "price is required")

                 (and (some? (:price params))
                      (not (number? (:price params))))
                 (conj "price must be a number"))]
    (if (empty? errors)
      {:ok true}
      {:ok false :errors errors})))

(defn coerce-book [params]
  (-> params
      (update :price (fn [p] (when p (double p))))
      (update :tags (fn [t] (cond
                              (nil? t)    []
                              (vector? t) t
                              (seq? t)    (vec t)
                              :else       [t])))))
