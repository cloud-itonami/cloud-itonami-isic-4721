(ns foodretailops.governor-test
  "Pure unit tests of `foodretailops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [foodretailops.advisor :as adv]
            [foodretailops.governor :as gov]
            [foodretailops.store :as store]))

(def store-1 {:store-id "store-1" :name "Main Street Butcher Shop" :registered? true :verified? true})
(def store-3 {:store-id "store-3" :name "Harbor Fishmonger" :registered? true :verified? false})

(defn- clean-proposal [op store-id]
  {:op op :store-id store-id :summary "s" :rationale "routine retail coordination"
   :cites [store-id] :effect :propose :value {} :confidence 0.85})

(deftest store-unregistered-is-hard
  (testing "no store record at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "unknown-store") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest store-unverified-is-hard
  (testing "store registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"store-3" store-3})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "store-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "store-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :finalize-food-safety-clearance "store-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest food-safety-clearance-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing a food-safety-clearance decision is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "finalized the food safety clearance decision for this line"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest allergen-exclusion-override-content-is-hard
  (testing "a proposal touching an allergen-exclusion-requirement override is HARD-blocked, same as clearance"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "decided to override allergen exclusion requirement for case 9"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest refrigeration-equipment-direct-control-content-is-hard
  (testing "a proposal touching direct refrigeration/processing-equipment actuation is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :schedule-staffing-operation "store-1")
                          :summary "actuate equipment: control the walk-in cooler remotely")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest health-authority-content-is-hard
  (testing "a proposal touching health-department/inspection-clearance/license enforcement is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "store-1")
                          :summary "contact health department for inspection clearance and license suspension review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-food-safety-concern-is-not-scope-excluded
  (testing "flagging observed spoilage/allergen-mismatch/temperature-abuse concerns as a FOOD SAFETY CONCERN (not a clearance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          concern (assoc (clean-proposal :flag-food-safety-concern "store-1")
                         :value {:concern "case 3 ground beef holding at 48F for 2 hours, suspected temperature abuse"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (spoilage/allergen/temperature) is exactly what this op exists to surface"))))

(deftest food-safety-concern-always-escalates-clean
  (testing ":flag-food-safety-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-food-safety-concern "store-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "store-1")
                           :value {:item "walk-in cooler repair" :estimated-cost 5000.0}
                           :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"store-1" store-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "store-1")
                       :value {:item "butcher paper" :estimated-cost 90.0}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------------------------------------------------
;; CRITICAL guardrail regression test: multiple sibling actors in this
;; fleet have independently discovered and fixed the SAME bug class --
;; a governor scope-exclusion term phrased as a bare noun (e.g. bare
;; "safety" or bare "equipment") accidentally matches inside the mock
;; advisor's OWN default rationale/disclaimer text for a legitimate,
;; allowed proposal, causing the actor to self-block on its own happy
;; path. This test asserts every default mock-advisor proposal, for
;; every op in the closed allowlist, at a REGISTERED+VERIFIED store,
;; clears the governor with `:scope-excluded` absent from its
;; violations (regardless of `:hard?`/`:escalate?` -- some ops legally
;; escalate, e.g. :flag-food-safety-concern, but MUST NOT self-trip the
;; scope-exclusion check to get there).
;; ----------------------------------------------------------------------
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals, for every allowed op, never trigger :scope-excluded"
    (let [s (store/mem-store {"store-1" store-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                  :flag-food-safety-concern]]
        (let [proposal (adv/infer nil {:op op :store-id "store-1"
                                        :patch {:units-sold 10 :item "test"
                                                :estimated-cost 90.0
                                                :concern "routine spoilage check"}})
              verdict (gov/check {:store-id "store-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default mock advisor's own proposal for " op
                   " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:rationale :summary])))))))))
