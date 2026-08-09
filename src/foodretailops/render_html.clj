(ns foodretailops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave0 Lane A): this repo previously had NO demo page and no generator
  at all. This namespace drives the REAL actor stack
  (`foodretailops.operation` -> `foodretailops.governor` ->
  `foodretailops.store`) through a scenario adapted from this repo's own
  `foodretailops.sim` demo driver (`clojure -M:dev:run`, confirmed BEFORE
  writing this file to produce a sensible ledger against the real seeded
  store ids `store-1`..`store-3` -- ids that DO match
  `foodretailops.store/demo-data`, so it was safe to reuse rather than
  author from scratch), trimmed to a representative subset (clean
  auto-commits, always-escalate ops that a human then approves, and four
  distinct HARD-hold reasons) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verify by diffing two consecutive
  runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [foodretailops.advisor :as advisor]
            [foodretailops.store :as store]
            [foodretailops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :food-retail-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "food-retail-coordinator-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: store-1 clears a clean sales-record log (phase-3
  auto-commit), a staffing-operation schedule (auto-commit), a low-cost
  supply-order coordination (auto-commit), a high-cost supply-order
  (ALWAYS escalates -- approved), and a food-safety-concern flag (ALWAYS
  escalates -- approved); store-99 HARD-holds as unregistered;
  store-3 HARD-holds as registered-but-unverified; a staffing proposal
  whose advisor injects `:effect :commit` HARD-holds on effect-not-propose;
  a sales-record proposal that drifts into food-safety-clearance /
  allergen-override scope HARD-holds permanently. Every HARD hold never
  reaches a human. Returns the resulting store -- every field read by
  `render` below is real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1-sales" {:op :log-sales-record :store-id "store-1"
                             :patch {:units-sold 30 :item "pork chops" :spoilage-units 0}})

    (exec! actor "t2-staff" {:op :schedule-staffing-operation :store-id "store-1"
                             :patch {:shift "morning-counter" :date "2026-07-20"
                                     :window "07:00-11:00"}})

    (exec! actor "t3-supply-low" {:op :coordinate-supply-order :store-id "store-1"
                                  :patch {:item "butcher paper" :quantity 200
                                          :estimated-cost 90.0}})

    (exec! actor "t4-supply-high" {:op :coordinate-supply-order :store-id "store-1"
                                   :patch {:item "walk-in cooler repair parts" :quantity 1
                                           :estimated-cost 2200.0}})
    (approve! actor "t4-supply-high")

    (exec! actor "t5-flag" {:op :flag-food-safety-concern :store-id "store-1"
                            :patch {:concern "case 3 ground beef holding at 48F for 2h, suspected temperature abuse"
                                    :confidence 0.92}})
    (approve! actor "t5-flag")

    (exec! actor "t6-unregistered" {:op :log-sales-record :store-id "store-99"
                                    :patch {:units-sold 0 :item "unknown"}})

    (exec! actor "t7-unverified" {:op :log-sales-record :store-id "store-3"
                                  :patch {:units-sold 10 :item "salmon fillet"}})

    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "t8-effect" {:op :schedule-staffing-operation :store-id "store-1"
                                       :patch {:shift "evening-counter" :date "2026-07-22"}}))

    (exec! actor "t9-scope" {:op :log-sales-record :store-id "store-1"
                             :out-of-scope? true
                             :patch {}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger store-id]
  (last (filter #(= (:store-id %) store-id) ledger)))

(defn- status-cell [ledger store-id]
  (let [f (last-fact-for ledger store-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (or (some-> f :violations first :rule)
                     (some-> f :basis first))]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- verified-cell [{:keys [registered? verified?]}]
  (cond
    (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
    registered? "<span class=\"warn\">registered, health permit pending</span>"
    :else "<span class=\"critical\">unregistered</span>"))

(defn- store-row [ledger {:keys [store-id kind] :as s}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc store-id) (esc (:name s)) (esc (some-> kind name))
          (verified-cell s)
          (status-cell ledger store-id)))

(defn- ledger-row [{:keys [t op store-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc store-id)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(defn- coordination-row [{:keys [op store-id value]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name (or op :n-a))) (esc store-id)
          (esc (pr-str (or value {})))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Features`, `foodretailops.governor`/`foodretailops.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-sales-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &amp; high confidence</span></td></tr>"
   "        <tr><td><code>:schedule-staffing-operation</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &amp; high confidence</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-order</code></td><td><span class=\"warn\">phase-3 auto when low cost; ALWAYS human approval when estimated-cost &gt; threshold</span></td></tr>"
   "        <tr><td><code>:flag-food-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; surfaces concern only, never finalizes clearance</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        stores (store/all-stores db)
        store-rows (str/join "\n" (map (partial store-row ledger) stores))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        coord-rows (str/join "\n" (map coordination-row (store/coordination-log db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4721 &middot; specialized-food-retail</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Retail sale of food in specialized stores (ISIC 4721) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · food-safety concern / high-cost supply always human-approved · HARD holds permanent</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Specialized food retail stores</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>foodretailops.store</code> via <code>foodretailops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated from the real actor stack.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Store</th><th>Name</th><th>Kind</th><th>Registration / health permit</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     store-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Food Retail Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden: store unverified (registration + health permit), effect not <code>:propose</code>, and permanently out-of-scope food-safety-clearance / allergen-override / equipment-actuation / authority-enforcement territory. An unregistered or unverified store never reaches a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log (this run)</h2>\n"
     "    <p class=\"muted\">Records written only after governor-clean commit (auto or human-approved).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Store</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     coord-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Store</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        parent (.getParentFile (java.io.File. out))]
    (when parent (.mkdirs parent))
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "coordination commits )")))
