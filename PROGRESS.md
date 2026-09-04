# Progress

**Current phase:** 1a — Schema and invariants
**Branch:** phase-1a-schema
**Last updated:** 2026-09-04 12:20

## Done in this phase
- [x] V2__ledger_core.sql: accounts, ledger_transactions, ledger_entries, the CHECK
  constraints, the two entry indexes, and the partial unique index uq_single_reversal.
  No version column — ec0107a
- [x] V3__balanced_transaction_trigger.sql: I1 as a deferred CONSTRAINT TRIGGER — 7b4f2d3
- [x] V4__immutability_triggers.sql: I5 on both ledger tables, RAISEs on UPDATE and
  DELETE — 5a5febb
- [x] V5__entry_currency_trigger.sql: I7 as a BEFORE INSERT trigger — 65943b1
- [x] Twelve schema tests in src/test/java/com/baran/ledger/schema, none @Transactional.
  Fifteen tests total with the phase 0 smoke tests; `./mvnw -B verify` green
- [x] Break proof for I1, I5 and I7: each trigger dropped through a throwaway migration,
  the corresponding test observed failing, the migration removed, the test observed
  passing again. Outputs are in the phase 1a report
- [x] Each of the four commits verified green on its own in a detached worktree

## In progress
- Nothing; the phase deliverables are complete

## Blocked / open questions
- PHASES.md exit criteria say "all eleven schema tests"; the list above it names twelve
  test methods. All twelve are implemented. Raised in the report, not a blocker
- The triggers were split into V3, V4 and V5 rather than living in V2. Raised in the
  report under Deviations

## Next step
- Push phase-1a-schema; do not merge into main (merges are user-only). Wait for the
  audit before phase 1b
