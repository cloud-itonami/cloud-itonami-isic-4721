(ns foodretailops.advisor-test
  "Unit tests of `foodretailops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [foodretailops.advisor :as adv]
            [foodretailops.store :as store]))

(def db (store/seed-db))

(deftest propose-sales-record-shape
  (testing "sales-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-sales-record
                           :store-id "store-1"
                           :patch {:units-sold 42 :item "ground beef"}})]
      (is (= :log-sales-record (:op p)))
      (is (= "store-1" (:store-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :store-id)))))

(deftest propose-staffing-operation-shape
  (testing "staffing-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staffing-operation
                           :store-id "store-2"
                           :patch {:shift "morning-counter" :date "2026-07-20"}})]
      (is (= :schedule-staffing-operation (:op p)))
      (is (= "store-2" (:store-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :store-id "store-1"
                           :patch {:item "butcher paper" :quantity 200 :estimated-cost 90.0}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-food-safety-concern-shape
  (testing "food-safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-food-safety-concern
                           :store-id "store-1"
                           :patch {:concern "spoilage suspected in case 3"}})]
      (is (= :flag-food-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :store-id "store-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                :flag-food-safety-concern]]
      (let [p (adv/infer db {:op op :store-id "store-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
