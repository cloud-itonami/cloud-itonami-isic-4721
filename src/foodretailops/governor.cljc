(ns foodretailops.governor
  "FoodRetailGovernor -- the independent compliance layer that earns the
  FoodRetailAdvisor the right to commit. The advisor has no notion of
  whether a store is actually registered and verified (i.e. its business
  registration AND health permit are on file), whether its own proposed
  `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (sales-record logging, staffing-operation scheduling, supply-order
  coordination, food-safety-concern flagging) for specialized food retail
  stores (butcher, bakery, fishmonger, greengrocer). It NEVER performs or
  authorizes:
    - finalizing a food-safety-clearance decision
    - overriding an allergen-exclusion requirement
    - direct refrigeration/slicing/processing-equipment actuation or
      control
    - food-safety-authority enforcement (health-department clearance,
      inspection sign-off, permit/license suspension, compliance
      enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human approval:

    1. Store unverified          -- the target store (business
                                    registration + health permit) record
                                    must exist AND be independently
                                    confirmed `:registered?`/`:verified?`
                                    in the store before ANY proposal for
                                    it may commit or even escalate. Never
                                    trusts a proposal's own claim about
                                    the store -- re-derived from the
                                    store's own record, the same 'ground
                                    truth, not self-report' discipline
                                    every sibling actor's governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST be
                                    `:propose`. Any other effect value
                                    is, by construction, a claim to
                                    directly actuate/commit outside
                                    governance -- HARD block, not merely
                                    low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                    whose op, rationale, summary,
                                    citations or draft value touches
                                    food-safety-clearance-finalization/
                                    allergen-exclusion-override/
                                    refrigeration-or-processing-equipment-
                                    actuation/food-safety-authority
                                    territory is a HARD, PERMANENT block
                                    -- this actor's charter excludes that
                                    territory structurally, not as a
                                    rollout milestone. Evaluated
                                    UNCONDITIONALLY on every proposal. An
                                    op outside the closed four-op
                                    allowlist is the SAME failure mode
                                    (an advisor proposing something it
                                    was never authorized to propose) and
                                    is folded into this same check.

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-food-safety-concern` -- ALWAYS escalates to a
      human, regardless of confidence, regardless of how clean the
      proposal otherwise is. This op only ever SURFACES a concern for a
      human -- it never itself finalizes any food-safety-clearance
      decision. `foodretailops.phase` independently agrees:
      `:flag-food-safety-concern` is never a member of any phase's
      `:auto` set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      procurement proposal always needs a human sign-off, even when the
      governor and phase would otherwise allow auto-commit.

  Scope-exclusion-term discipline (see ADR-2607121000 / sibling actors'
  own fix history): terms are phrased as the finalization/execution
  ACTION (e.g. \"finalize clearance\", \"control the slicer\"), never as
  a bare noun (e.g. bare \"safety\" or bare \"equipment\") -- a bare noun
  would accidentally match this SAME namespace's own default mock-advisor
  disclaimer text for a legitimate, allowed proposal (e.g. `advisor`'s
  own \"...食品安全認可の判断なし\" / \"...加工設備の直接操作は行わない\"
  rationale strings), causing the actor to self-block on its own happy
  path. The refrigeration/processing-equipment-actuation terms below are
  deliberately kept ENGLISH-ONLY (no Japanese equivalent) because this
  namespace's own Japanese default proposal rationale text legitimately
  discusses NOT touching such equipment -- see
  `foodretailops-scope-exclusion-test`'s dedicated
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` test,
  which asserts every default op's proposal clears this check."
  (:require [clojure.string :as str]
            [foodretailops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example specialized-food-retail procurement threshold (USD-equivalent
  units, domain-illustrative -- not a universal cross-domain constant). A
  `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  this value ALWAYS escalates to human sign-off, regardless of confidence
  or rollout phase."
  500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). No op in
  this set ever finalizes a food-safety-clearance decision -- that
  authority is permanently out of scope, see `scope-excluded-terms`."
  #{:log-sales-record :schedule-staffing-operation
    :coordinate-supply-order :flag-food-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not.
  `:flag-food-safety-concern` only ever SURFACES a concern -- it must
  never be auto-commit-eligible, in any phase, for any confidence."
  #{:flag-food-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing a food-safety-
  clearance decision, overriding an allergen-exclusion requirement,
  directly actuating refrigeration/slicing/processing equipment, or
  food-safety-authority enforcement. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  Every term is phrased as the finalization/execution ACTION, not a bare
  noun -- see the namespace docstring's self-trip-avoidance note."
  ["food safety clearance" "food-safety-clearance" "finalize clearance"
   "finalized clearance" "clearance decision" "food safety approval"
   "食品安全認可の確定" "食品安全認可を確定" "衛生証明書の発行" "衛生証明の発行"
   "override allergen" "overrides allergen" "overrode allergen"
   "allergen exclusion override" "allergen-exclusion override"
   "waive allergen" "bypass allergen" "ignore allergen" "allergen override"
   "アレルゲン除外" "アレルゲン上書き" "アレルゲン無視"
   "refrigeration equipment override" "direct equipment control"
   "actuate equipment" "equipment override" "shut down equipment"
   "power off equipment" "control the walk-in cooler" "control the display case"
   "control the slicer" "control the meat grinder" "control the freezer"
   "health department" "health inspector" "inspection clearance"
   "regulatory clearance" "compliance sign-off" "license suspension"
   "license-suspension" "permit revocation" "permit-revocation"
   "保健所" "衛生当局" "営業許可取消" "違反"])

;; ----------------------------- checks -----------------------------

(defn- store-unverified-violations
  "The target store (business registration + health permit) must exist
  AND be independently `:registered?`/`:verified?` in the store -- never
  trust the proposal's own `:store-id` claim without a store lookup."
  [{:keys [store-id]} st]
  (let [s (store/store-record st store-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :store-unverified
        :detail (str store-id " は未登録または未検証の店舗(営業登録/衛生許可) -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches food-safety-clearance-finalization/
  allergen-exclusion-override/refrigeration-or-processing-equipment-
  actuation/food-safety-authority territory, regardless of confidence or
  how clean every other check is. Evaluated UNCONDITIONALLY on every
  proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "食品安全認可の確定/アレルゲン除外要件の上書き/冷蔵・加工設備の直接操作/食品安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above `supply-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a FoodRetailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [store-id (or (:store-id proposal) (:store-id request))
        hard (into []
                   (concat (store-unverified-violations {:store-id store-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :store-id   (:store-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
