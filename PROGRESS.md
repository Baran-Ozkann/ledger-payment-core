# Progress

**Current work:** `entry_id` and `created_at` on the account activity event, for an external
reconciliation service
**Branch:** event-entry-reference (branched from main, which carries phases 0 to 6)
**Last updated:** 2026-09-24

## Entry reference on the activity event

- [x] `OutboxRelayTest.deliveredPayloadKeepsTheContractShape` reads the delivered payload as plain
  JSON and pins every key with its JSON type. An external reader parses the bytes, not the record
- [x] README diagram names the topic `ledger.account-activity`, as `EventTopics` declares it; it
  said `account.activity`, and a consumer subscribed to that
- [x] `EntryRepository.insert` returns the entry's `id` and `created_at` through `RETURNING`, and
  `LedgerService.announce` puts them on the event. A reversal's events name the reversal's own
  entries. `created_at` is always six fractional digits in UTC, pinned with `@JsonFormat`
- [x] Both new record components are boxed. A five-field event from before the change reads as
  null, not as entry zero, and the projection still applies it
- [x] README "Event contract" section: key, the required `event-id` header, the seven fields, and
  that older events carry five

### Break proof

Each mechanism broken, the failure recorded, then restored and passing:

- `tx_type` renamed through `@JsonProperty("type")`: `deliveredPayloadKeepsTheContractShape` fails,
  `expected {…"tx_type"="string"} but was {…"type"="string"}`. `relayPublishesAndMarks` stayed green
- The debit and credit references swapped in `post`: `eventNamesTheEntryItDescribes` fails with
  entry ids 3 and 4 exchanged between the two accounts
- The reversal announcing the original transaction's entries: `reversalEventsNameTheReversalsEntries`
  fails, `entryId=8/7` where the reversal wrote `10/9`
- `@JsonFormat` removed: `createdAtAlwaysCarriesSixFractionalDigits` fails,
  `expected "2026-09-24T00:00:00.000000Z" but was "2026-09-24T00:00:00Z"`. The two live-transfer
  tests passed in that run, because their instants did not happen to end in zero; that is why the
  fixed-instant test exists
- `created_at` truncated to milliseconds: both live-transfer tests fail, `.905125Z` stored against
  `.905000Z` announced
- `entry_id` made `required` on read: the projection logs `Missing required creator property
  'entry_id'` and `eventWrittenBeforeTheEntryReferenceIsStillApplied` times out after a minute

### Verification

- `./mvnw -B verify`: **97 tests, 0 failures, 2 m 49 s**
- `bash ci/check-rules.sh`: exits 0

---

# Phase 6 — Packaging

**Branch:** phase-6-docs (branched from main, which carries phases 0 to 5)
**Last updated:** 2026-09-08

## Done in this phase

- [x] `CLAUDE.md` → `CONVENTIONS.md` and `PHASES.md` → `docs/roadmap.md`, both with `git mv` so the
  history of every rule and phase definition follows the file. Every reference updated:
  `ci/check-rules.sh`, `PROGRESS.md`, `docs/future.md`, `AppRolePrivilegesTest`, and roadmap's own
  copy of the guard. The guard's AI-reference check excluded the old two by name; `roadmap.md` now
  matters more than the old exclusion did, because it sits inside the `docs` tree that check searches
- [x] The ledger is a compose service. Two-stage `Dockerfile` (JDK to build, JRE to run, non-root),
  `application-compose.yml` pointing the datasource, the broker and the OTLP exporter at service
  names. API published on `127.0.0.1:8080`; the management connector is not published at all, so the
  exposure the default profile accepts knowingly does not exist there
- [x] Kafka carries two client listeners. A broker advertises one address per listener, and a
  container reaching it by service name cannot share one with a host process reaching a published
  port. `HOST` advertises `127.0.0.1:9092`, `DOCKER` advertises `kafka:29092`
- [x] `ops/demo/seed.sh` plus the `demo-accounts` service: runs once, creates alice and bob through
  the API, funds alice, prints the transfer curl with their ids filled in. Fixed Idempotency-Keys, so
  bringing the stack up again replays those four requests rather than creating four more accounts
- [x] PostgreSQL published on **5433** by default rather than 5432, and the default datasource url
  follows. Phase 5 already moved it for the load runs; a machine with a native PostgreSQL answers on
  5432 and a misdirected connection looks entirely reasonable and means nothing
- [x] Prometheus carries a scrape target for each place the application can run — `ledger:8081` and
  `host.docker.internal:8081` — because the load harness still runs it on the host. The one that is
  not running shows as down
- [x] Nine ADRs in `docs/adr/`, each naming a specific rejected alternative and the concrete failure
  it produces. ADR-004 carries the phase 5 numbers for both regimes and does **not** conclude that
  ordered locking wins; ADR-006 carries the hot-account measurement; ADR-009 carries its own break
  proof
- [x] README rewritten: architecture as Mermaid, the eight invariants and seven rules with their
  enforcement and their test, quick start, API reference, test strategy, the break-proof table, k6
  results with charts, the trace screenshot, and known limits stated plainly
- [x] README section on what a green suite did not catch: the rule guard's grep exit code, container
  reuse passing with Flyway disabled, and the deprecated OTLP property that sent zero spans while 84
  tests were green
- [x] `load/run.py` connects as `ledger_app`, the role the application ships with, rather than the
  `ledger` owner it inherited from before `V12`. The four runs in `load/RESULTS.md` were taken as the
  owner and were **not** re-run: the report now says so, and says that whether the grant set moves a
  number is unmeasured. The `docs/future.md` deferral is removed, the deferral having been taken
- [x] `docs/future.md` trimmed to what is still future. The ADR-003 and ADR-005 drafts became those
  records; four items had been built or fixed since they were written
- [x] Cleanup: `AccountRepository.credit` no longer returns a row count nobody read, `MAX_PAGE_SIZE`
  is private, `config/.gitkeep` removed, two stale comments repointed

## Break proof — ADR-009, V1 / V3 / V7

The three checks were deleted from `LedgerService.post`, a temporary test class recorded what the
invariants then allow, and everything was restored afterwards.

- V1 with a **funded** destination: `201 CREATED`, source 1000 → 1100, destination 5000 → 4900. The
  money moved out of the account named as the destination. I2 zero, no account drifting
- V3: `201 CREATED`, balance unchanged, two entries recording a movement that never happened
- V7: `500` from the I8 trigger at commit — the invariant that exists, and the 500 that V7 turns into
  a clean 422
- `TransferValidationTest`: 4 failures broken, 8 passes restored. Both outputs pasted into ADR-009

Worth noting for the reviewer: `negativeAmountRejected` funds its destination with nothing, so on the
broken build it fails with a `500` from I4's CHECK rather than a `201`. That is I4 catching a
negative amount by accident, in the one case where the attack is pointless — which is why the
temporary test funded the destination.

## Verification

- `./mvnw -B verify`: **92 tests, 0 failures, 1 m 35 s**
- `bash ci/check-rules.sh`: exits 0
- Exit criterion checked end to end: `docker compose up -d --build`, then the single printed curl
  returned `201` with the two entries; repeating it returned the same transaction id; bob's balance
  read 1250; the projection showed both accounts; Tempo held the traces; the `ledger:8081` scrape
  target was up

## In progress

- Nothing. The phase is complete; the branch is pushed and the report is written

## Blocked / open questions

- **Commit trailers.** The session harness asks for a `Co-Authored-By` trailer and a session link on
  every commit. `CONVENTIONS.md` forbids AI tool references in commit messages, so the repository
  rule was followed and no trailer was added. Flagged rather than decided quietly
- **Mutation testing (PIT) was dropped**, which the roadmap allows as the one optional item. The
  reasoning — container cost per mutant, and that a Java mutation operator cannot reach a trigger,
  a constraint or a grant — is in `docs/future.md`
