(ns crux-shop.system-tests
  (:require
    [clojure.test :refer [deftest testing is use-fixtures]]
    [crux-shop.system :as system]
    [crux-shop.test-utils :refer [simplify]]
    [com.stuartsierra.component :as component]
    [com.walmartlabs.lacinia :as lacinia]))

(defn ^:private test-system
  "Creates a new system suitable for testing, and ensures that
  the HTTP port won't conflict with a default running system."
  []
  (-> (system/new-system)
      (assoc-in [:server :port] 8989)))

(def ^:dynamic ^:private *system*)

(use-fixtures :once
  (fn [test-fn]
    (binding [*system* (component/start-system (test-system))]
      (try
        (test-fn)
        (finally
          (component/stop-system *system*))))))

(defn ^:private q
  "Extracts the compiled schema and executes a query."
  ([query]
   (q query nil))
  ([query variables]
   (-> *system*
       (get-in [:schema-provider :schema])
       (lacinia/execute query variables nil)
       simplify)))

(deftest query-all-test
  (testing "can query all items in the db"
    (let [expected {:data
                    {:all_items
                     [{:id "moldy-bread",
                       :name "Moldy bread",
                       :description "This isn't safe to eat"}]}}
          actual (q "{all_items {id name description}}")]
      (is (= expected actual)))))

(deftest add-item-test
  (testing "can add item to db"
    (let [expected {:data
                    {:add_item
                     {:id "pasta", :name "pasta", :description "Delicious pasta"}}}
          actual (q "mutation {add_item(id: \"pasta\", name: \"pasta\", description: \"Delicious pasta\") {id name description}}")]
      (is (= expected actual)))

    (testing "item is actually added to the db"
      (is (-> (q "{all_items {id name description}}")
              :data
              :all_items
              set
              (contains? {:id "pasta", :name "pasta", :description "Delicious pasta"}))))))

(deftest item-by-id-test
  (testing "can get item by id"
    (let [expected {:data
                    {:item_by_id
                     {:id "moldy-bread"
                      :name "Moldy bread"
                      :description "This isn't safe to eat"}}}
          actual (q "{item_by_id(id: \"moldy-bread\"){id name description}}")]
      (is (= expected actual)))))

(deftest add-quantity-test
  (testing "can update quantity of item already in db"
    (let [expected {:data
                    {:update_quantity
                     {:id "moldy-bread"
                      :quantity 1}}}
          actual (q "mutation {update_quantity(id: \"moldy-bread\", quantity: 1) {id quantity}}")]
      (is (= expected actual)))

    (testing "no data is lost in the transaction"
      (Thread/sleep 100)
      (is (= {:data
              {:item_by_id
               {:id "moldy-bread"
                :name "Moldy bread"
                :quantity 1
                :description "This isn't safe to eat"}}}
             (q "{item_by_id(id: \"moldy-bread\"){id name quantity description}}"))))))

(deftest sell-item-test
  (q "mutation {update_quantity(id: \"moldy-bread\", quantity: 10) {id quantity}}")
  (Thread/sleep 100)

  (testing "can sell an item"
    (let [expected {:data {:sell_item {:id "moldy-bread", :quantity 2}}}
          actual (q "mutation {sell_item(id: \"moldy-bread\", quantity: 2) {id quantity}}")]
      (is (= expected actual))))

  (Thread/sleep 100)
  (testing "no data is lost in the transaction"
    (is (= {:data
            {:item_by_id
             {:id "moldy-bread"
              :name "Moldy bread"
              :quantity 8
              :description "This isn't safe to eat"}}}
           (q "{item_by_id(id: \"moldy-bread\"){id name quantity description}}"))))

  (testing "can sell an item without specifying quantity"
    (let [expected {:data {:sell_item {:id "moldy-bread", :quantity nil}}}
          actual (q "mutation {sell_item(id: \"moldy-bread\") {id quantity}}")]
      (is (= expected actual))))

  (Thread/sleep 100)
  (testing "no data is lost in the transaction"
    (is (= {:data
            {:item_by_id
             {:id "moldy-bread"
              :name "Moldy bread"
              :quantity 7
              :description "This isn't safe to eat"}}}
           (q "{item_by_id(id: \"moldy-bread\"){id name quantity description}}")))))

(deftest transaction-test
  (let [all-item-q "{all_items {id name quantity price}}"
        all-transaction-q "{all_transactions {id quantity item amount type}}"
        balance-q "{shop_balance}"
        sell-m "mutation {sell_item(id: \"moldy-bread\", quantity: 1) {id}}"]

    (testing "stock before"
      (is (= {:data
              {:all_items
               [{:id "moldy-bread", :name "Moldy bread", :quantity 10, :price 100}]}}
             (q all-item-q))))

    (testing "transactions before"
      (is (= {:data {:all_transactions []}}
             (q all-transaction-q))))

    (testing "balance before"
      (is (= {:data {:shop_balance 0}}
             (q balance-q))))

    (testing "sell an item"
      (is (= {:data {:sell_item {:id "moldy-bread"}}}
             (q sell-m))))

    (Thread/sleep 100)

    (testing "stock after"
      (is (= {:data
              {:all_items
               [{:id "moldy-bread", :name "Moldy bread", :quantity 9, :price 100}]}}
             (q all-item-q))))

    (testing "transactions after"
      (is (= [{:quantity 1,
               :item "moldy-bread"
               :amount 100
               :type :PURCHASE}]
             (->> (q all-transaction-q)
                  :data
                  :all_transactions
                  (map #(dissoc % :id))))))

    (testing "balance after"
      (is (= {:data {:shop_balance 100}}
             (q balance-q))))))
