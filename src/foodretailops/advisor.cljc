(ns foodretailops.advisor
  "FoodRetailAdvisor -- the *contained intelligence node* for the ISIC-4721
  specialized food retail operations-coordination actor (butcher shops,
  bakeries, fishmongers, greengrocers).

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: sales-record logging (inventory/sale/spoilage), staffing
  scheduling (floor-staff/delivery), supply-order coordination (inventory
  procurement), and food-safety-concern flagging. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale +
  the fields it cited), never a committed record and NEVER a direct
  actuation -- every proposal's `:effect` is always `:propose`. Every
  output is censored downstream by `foodretailops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts a food-safety-clearance decision, an
  allergen-exclusion-requirement override, direct refrigeration/slicing/
  processing-equipment actuation, or any other food-safety-authority
  action (health-department clearance, inspection sign-off, permit/
  license decisions) -- those are permanently out of scope for this
  actor, not merely un-implemented. `foodretailops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :store-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-sales-record
  "Draft an inventory/sale/spoilage log entry. Pure logging of observed
  operations (units sold, stock on hand, spoilage/waste counted) -- never
  a food-safety-clearance judgement."
  [_db {:keys [store-id patch]}]
  {:op         :log-sales-record
   :store-id   store-id
   :summary    (str store-id " の売上/在庫/廃棄記録を記録: " (pr-str (keys patch)))
   :rationale  "販売数量・在庫・廃棄(スポイレッジ)の観察記録のみ。食品安全認可の判断なし。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a floor-staff/delivery scheduling proposal (a calendar/roster
  entry, never a direct refrigeration/slicing/processing-equipment
  actuation)."
  [_db {:keys [store-id patch]}]
  {:op         :schedule-staffing-operation
   :store-id   store-id
   :summary    (str store-id " の売場スタッフ/配送オペレーション予定を提案: " (pr-str (keys patch)))
   :rationale  "売場人員配置・配送スケジュールの調整提案のみ。加工設備の直接操作は行わない。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an inventory procurement coordination request (meat, fish,
  produce, baked-goods stock, packaging -- never a finalized purchase
  order; a human always confirms procurement)."
  [_db {:keys [store-id patch]}]
  {:op         :coordinate-supply-order
   :store-id   store-id
   :summary    (str store-id " に関連する仕入れ調達オーダーを提案: " (pr-str (keys patch)))
   :rationale  "食材・包装資材などの調達調整提案のみ。確定発注は人間が行う。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.90})

(defn- propose-food-safety-concern
  "Surface a food-safety concern (spoilage, allergen mismatch, suspected
  contamination) for HUMAN triage. This op ALWAYS escalates in
  `foodretailops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real."
  [_db {:keys [store-id patch]}]
  {:op         :flag-food-safety-concern
   :store-id   store-id
   :summary    (str store-id " の食品安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "腐敗・アレルゲン不一致・異物混入疑い等の観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-sales-record (propose-sales-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-food-safety-concern (propose-food-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the food safety clearance decision and applied an allergen exclusion override")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :store-id (:store-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
