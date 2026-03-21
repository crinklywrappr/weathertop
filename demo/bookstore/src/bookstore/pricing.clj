(ns bookstore.pricing)

(defprotocol Pricer
  (display-price [this base-price]
    "Return the display price for base-price given this pricing strategy."))

(defrecord FullPrice []
  Pricer
  (display-price [_this base-price] base-price))

(defrecord PercentOff [pct]
  Pricer
  (display-price [this base-price]
    (- base-price (* base-price (/ (:pct this) 100.0)))))

(defrecord FixedOff [amount]
  Pricer
  (display-price [this base-price]
    (max 0.0 (- base-price (:amount this)))))

(defn- sale-book? [book]
  (or (some #{"sale"} (:tags book))
      (> (:price book) 59.99)))

(defn pricer-for [book]
  (cond
    (some #{"sale"} (:tags book)) (->PercentOff 15)
    (sale-book? book)              (->FixedOff 10)
    :else                          (->FullPrice)))
