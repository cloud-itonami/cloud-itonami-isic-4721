# cloud-itonami-isic-4721

**Retail sale of food in specialized stores** — ISIC Rev.4 class 4721.

A coordination-only actor for specialized food retail stores — butcher shops, bakeries, fishmongers, and greengrocers (distinct from stand-alone beverage retail, ISIC 4722, and tobacco retail, ISIC 4723) — behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`, `schedule-staffing-operation`, `coordinate-supply-order`, `flag-food-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** — the target store's business registration AND health permit must exist AND be independently registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing a food-safety-clearance decision, overriding an allergen-exclusion requirement, direct refrigeration/slicing/processing-equipment actuation, and food-safety-authority enforcement (health-department clearance, inspection sign-off, license/permit actions) are permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-food-safety-concern` — ALWAYS escalates, regardless of confidence or phase. A "flag a concern" op is never auto-commit-eligible and never finalizes a food-safety-authority decision itself — it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold — a large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals (food-safety concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Out of scope (structural, not a rollout milestone)

This actor is **operations coordination only**. It never performs or authorizes:

- Finalizing a food-safety-clearance decision.
- Overriding an allergen-exclusion requirement.
- Direct refrigeration/slicing/processing-equipment actuation or control (walk-in coolers, display cases, slicers, meat grinders, etc.).
- Food-safety-authority enforcement (health-department clearance, inspection sign-off, license suspension, compliance enforcement).

The governor's `scope-exclusion-violations` check re-scans every proposal for this failure mode independently of the advisor's own framing, and treats it as a HARD, permanent block regardless of confidence or how clean everything else is.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/foodretailops/governor_test.clj` — unit tests of governor hard checks, scope exclusion, and a dedicated regression test asserting the default mock-advisor proposals never self-trip scope-exclusion
- `test/foodretailops/advisor_test.clj` — advisor proposal shape and consistency
- `test/foodretailops/phase_test.clj` — rollout phase logic
- `test/foodretailops/governor_contract_test.clj` — full graph integration, audit trail
- `test/foodretailops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `foodretailops.store` — SSoT (MemStore, String-keyed store directory, append-only ledger)
- `foodretailops.advisor` — contained intelligence node (mock + real-LLM seam)
- `foodretailops.governor` — independent compliance layer
- `foodretailops.phase` — staged rollout (0→3)
- `foodretailops.operation` — langgraph-clj StateGraph
- `foodretailops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 2 (coordination/logistics/trade) fleet. See ADR-2607121000 and ADR-2670004721 (`cloud-itonami-isic-4721-specialized-food-retail-coverage`) for design decisions.
