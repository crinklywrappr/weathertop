(ns bookstore.db)

(def ^:private store
  (atom
   {"a1b2c3d4-0001-0000-0000-000000000001"
    {:id "a1b2c3d4-0001-0000-0000-000000000001"
     :title "The Pragmatic Programmer"
     :author "David Thomas"
     :tags ["programming" "career" "software"]
     :price 49.95}

    "a1b2c3d4-0002-0000-0000-000000000002"
    {:id "a1b2c3d4-0002-0000-0000-000000000002"
     :title "Clean Code"
     :author "Robert C. Martin"
     :tags ["programming" "software" "design"]
     :price 39.99}

    "a1b2c3d4-0003-0000-0000-000000000003"
    {:id "a1b2c3d4-0003-0000-0000-000000000003"
     :title "Design Patterns"
     :author "Gang of Four"
     :tags ["programming" "design" "patterns"]
     :price 54.99}

    "a1b2c3d4-0004-0000-0000-000000000004"
    {:id "a1b2c3d4-0004-0000-0000-000000000004"
     :title "Refactoring"
     :author "Martin Fowler"
     :tags ["programming" "software" "design"]
     :price 44.99}

    "a1b2c3d4-0005-0000-0000-000000000005"
    {:id "a1b2c3d4-0005-0000-0000-000000000005"
     :title "The Mythical Man-Month"
     :author "Fred Brooks"
     :tags ["career" "management" "software"]
     :price 34.99}

    "a1b2c3d4-0006-0000-0000-000000000006"
    {:id "a1b2c3d4-0006-0000-0000-000000000006"
     :title "Introduction to Algorithms"
     :author "Cormen et al."
     :tags ["algorithms" "programming" "theory"]
     :price 79.99}

    "a1b2c3d4-0007-0000-0000-000000000007"
    {:id "a1b2c3d4-0007-0000-0000-000000000007"
     :title "Structure and Interpretation of Computer Programs"
     :author "Abelson & Sussman"
     :tags ["programming" "theory" "lisp"]
     :price 64.99}

    "a1b2c3d4-0008-0000-0000-000000000008"
    {:id "a1b2c3d4-0008-0000-0000-000000000008"
     :title "Domain-Driven Design"
     :author "Eric Evans"
     :tags ["design" "patterns" "software"]
     :price 49.99}

    "a1b2c3d4-0009-0000-0000-000000000009"
    {:id "a1b2c3d4-0009-0000-0000-000000000009"
     :title "Working Effectively with Legacy Code"
     :author "Michael Feathers"
     :tags ["programming" "software" "refactoring"]
     :price 44.99}

    "a1b2c3d4-000a-0000-0000-00000000000a"
    {:id "a1b2c3d4-000a-0000-0000-00000000000a"
     :title "A Philosophy of Software Design"
     :author "John Ousterhout"
     :tags ["design" "software" "career"]
     :price 29.99}}))

(defn all-books []
  (vals @store))

(defn find-book [id]
  (get @store id))

(defn books-by-tags [tags]
  (let [tag-set (set tags)]
    (filter (fn [book]
              (some tag-set (:tags book)))
            (vals @store))))

(defn insert-book! [book]
  (swap! store assoc (:id book) book)
  book)

(defn update-book! [id fields]
  (get (swap! store update id merge fields) id))

(defn remove-book! [id]
  (let [book (find-book id)]
    (when book
      (swap! store dissoc id))
    book))
