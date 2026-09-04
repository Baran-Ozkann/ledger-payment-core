# Phase Plan — Ledger Payment Core

Eight sessions. Each ends with a working, tested, mergeable increment.
Phases 1a–4 cannot be compressed. Phases 5–6 can be compressed under time pressure.

| Phase | Branch | Duration | Break proof |
|---|---|---|---|
| 0 — Skeleton | `phase-0-skeleton` | ~1 week | — |
| 1a — Schema and invariants | `phase-1a-schema` | ~1 week | **required** |
| 1b — Domain, service, API | `phase-1b-service` | ~1.5 weeks | — |
| 2 — Idempotency and concurrency | `phase-2-concurrency` | ~1.5 weeks | **required** |
| 3 — Outbox and events | `phase-3-outbox` | ~1 week | **required** |
| 4 — Reversal, reconciliation, observability | `phase-4-recon-observability` | ~1.5 weeks | — |
| 5 — Load testing | `phase-5-load` | ~1 week | — |
| 6 — Packaging | `phase-6-docs` | ~1 week | — |

---

## PHASE 0 — Skeleton
**Branch:** `phase-0-skeleton`

### Goal
Have the entire infrastructure standing and CI green before any real code is written. No later phase should be spent fighting infrastructure.

### Deliverables
- Maven project, Java 21+, latest stable Spring Boot
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-actuator`, `postgresql`, `flyway-core`, `flyway-database-postgresql`, `testcontainers` (junit-jupiter + postgresql), `assertj`
- Package layout (created but empty):
  ```
  com.baran.ledger
  ├── LedgerApplication.java
  ├── api/          controllers, DTOs, error handling
  ├── domain/       value objects, enums
  ├── store/        JdbcClient-based repositories
  ├── service/      business flows
  ├── outbox/       (Phase 3)
  ├── projection/   (Phase 3)
  ├── recon/        (Phase 4)
  └── config/
  ```
- `docker-compose.yml`: PostgreSQL 16, Kafka (KRaft mode, **no** Zookeeper)
- `V1__baseline.sql` — empty baseline migration
- `AbstractIntegrationTest` — Testcontainers PostgreSQL via `@ServiceConnection`, **container reuse disabled**
- One smoke test: context starts, Flyway ran, `/actuator/health` is UP. The Flyway assertion must prove Flyway ran **in this run** (e.g. assert the `installed_on` timestamp is after JVM start), not merely that a row exists.
- `docs/.gitkeep` and a `.gitkeep` in every empty package, so no rule-guard path is ever missing
- `.github/workflows/ci.yml`: `mvn verify` **plus the rule guard below**
- `PROGRESS.md` (see template at the end of this document)
- `.gitignore`, skeleton `README.md`

### The CI rule guard

`CLAUDE.md` is only intent until CI enforces it. Add this as a step in the workflow, before `mvn verify`:

```bash
#!/usr/bin/env bash
# ci/check-rules.sh
set -uo pipefail
violation=0

deny() {
  local desc="$1"; shift
  local out status
  out=$(grep -rn "$@" 2>&1)
  status=$?
  case $status in
    0)
      echo "RULE VIOLATION: $desc"
      echo "$out" | head -20
      violation=1
      ;;
    1)
      : # no match, clean
      ;;
    *)
      # grep could not complete the search - most often a path in the list does
      # not exist. It exits 2 EVEN WHEN IT FOUND MATCHES, so treating a non-zero
      # status as "clean" would silently disable the check. Fail loudly instead.
      echo "GUARD ERROR: could not run check '$desc' (grep exit $status)"
      echo "$out" | head -5
      violation=1
      ;;
  esac
}

deny "floating point or BigDecimal in domain/service" \
  -E '\b(double|float|BigDecimal)\b' --include='*.java' \
  src/main/java/com/baran/ledger/domain src/main/java/com/baran/ledger/service

deny "@Transactional in a concurrency or idempotency test" \
  '@Transactional' --include='*Concurrency*.java' --include='*Idempotency*.java' src/test/java

deny "TODO/FIXME left in source" \
  -E 'TODO|FIXME' --include='*.java' --include='*.sql' src

deny "AI tool reference in committed content" \
  -iE 'co-authored-by|generated (by|with)|anthropic|copilot|chatgpt' \
  --exclude='CLAUDE.md' --exclude='PHASES.md' --exclude='PROGRESS.md' \
  src docs README.md

deny "JPA/Hibernate dependency present" \
  -E 'starter-data-jpa|hibernate' pom.xml

deny "optimistic locking version column" \
  -iE '\bversion\b\s+BIGINT' --include='*.sql' src/main/resources/db/migration

exit $violation
```

Two things make this correct, and both were wrong in the first version of this plan:

**A check that cannot run must fail the build.** `grep -rn` exits 2 when any path in its list is missing — and it does so *even when it found matches in the paths that do exist*. The original helper tested only whether grep succeeded, so exit 2 read as "clean" and the check became a silent no-op that reported success. Distinguishing 0 / 1 / everything-else is the fix.

**Every path in every check must exist from Phase 0 onward.** Commit `docs/.gitkeep` and `.gitkeep` in each empty package directory so no `deny` call is ever handed a missing path. If a later phase adds a path that does not exist yet, the guard now says so out loud instead of quietly passing.

### Pitfalls
- **Do not enable Testcontainers container reuse.** A warm container carries schema and rows across runs, which turns "the table exists" and "the sum of all entries is zero" into assertions about history rather than about this run. Verified failure mode: with reuse on, the full suite passes with Flyway *entirely disabled*, because the previous run's `flyway_schema_history` is still there. Phase 1a's trigger tests and Phase 2's property tests are exactly what a warm database renders meaningless. The few seconds saved are not worth a false green in a project whose only output is proof.
- Any assertion about migrations must be about the current run, not about state that could have survived from an earlier one.
- Give compose services **no** `container_name`. Hardcoding it defeats `COMPOSE_PROJECT_NAME`, so the Builder's and the Auditor's stacks collide.
- Commit a Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) so a clean checkout can run the build without Maven preinstalled.
- Put Kafka in compose but **do not wire it into tests** yet. A Kafka container before Phase 3 only inflates CI time.
- Spring Boot 3.1+ `@ServiceConnection` removes the need for hand-written `@DynamicPropertySource`. Use it.
- Agents reflexively add `spring-boot-starter-data-jpa`. It is banned; the rule guard catches it.

### Exit criteria
- [ ] `mvn verify` green locally and on GitHub Actions
- [ ] `ci/check-rules.sh` runs in CI and exits 0
- [ ] `docker compose up -d` brings Postgres and Kafka to healthy
- [ ] Smoke test verifies Flyway ran and health is UP
- [ ] `PROGRESS.md` present and filled
- [ ] Spring Boot and Java versions recorded in the report

---

## PHASE 1a — Schema and invariants
**Branch:** `phase-1a-schema` · **BREAK PROOF REQUIRED**

### Goal
Every database-level defense exists and is proven to actually fire. No application code yet — tests talk to the database directly through `JdbcClient`.

Splitting this out matters: if a trigger is subtly wrong, catching it here is cheap. Catching it after the service layer is built on top is not.

### Deliverables

**`V2__ledger_core.sql`:**

```sql
CREATE TABLE accounts (
    id             BIGSERIAL   PRIMARY KEY,
    public_id      UUID        NOT NULL UNIQUE,
    account_type   TEXT        NOT NULL,
    owner_ref      TEXT,
    currency       CHAR(3)     NOT NULL,
    balance        BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balance_sign CHECK (allow_negative OR balance >= 0),
    CONSTRAINT valid_account_type CHECK (
        account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE'))
);

CREATE TABLE ledger_transactions (
    id          BIGSERIAL   PRIMARY KEY,
    public_id   UUID        NOT NULL UNIQUE,
    tx_type     TEXT        NOT NULL,
    reverses_id BIGINT      REFERENCES ledger_transactions(id),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT valid_tx_type CHECK (
        tx_type IN ('TRANSFER','REVERSAL','FUNDING','FEE','ADJUSTMENT')),
    CONSTRAINT reversal_has_target CHECK (
        (tx_type = 'REVERSAL') = (reverses_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_single_reversal
    ON ledger_transactions(reverses_id) WHERE reverses_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES ledger_transactions(id),
    account_id     BIGINT      NOT NULL REFERENCES accounts(id),
    amount         BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT amount_nonzero CHECK (amount <> 0),
    CONSTRAINT amount_bounded CHECK (amount BETWEEN -10000000000 AND 10000000000)
);

CREATE INDEX idx_entries_account ON ledger_entries(account_id, id DESC);
CREATE INDEX idx_entries_tx      ON ledger_entries(transaction_id);
```

Note: **no `version` column.** Concurrency is handled by ordered pessimistic locks plus conditional UPDATE.

**Triggers:**

I1 — balanced transaction, checked at commit:
```sql
CREATE OR REPLACE FUNCTION assert_tx_balanced() RETURNS TRIGGER AS $$
DECLARE s BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO s
    FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF s <> 0 THEN
        RAISE EXCEPTION 'unbalanced transaction %: sum=%', NEW.transaction_id, s;
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_tx_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_tx_balanced();
```

I5 — immutability. **Must RAISE, not silently ignore:**
```sql
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger records are immutable: % on %', TG_OP, TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
```

I7 — entry currency matches account currency:
```sql
CREATE OR REPLACE FUNCTION assert_entry_currency() RETURNS TRIGGER AS $$
DECLARE acct_ccy CHAR(3);
BEGIN
    SELECT currency INTO acct_ccy FROM accounts WHERE id = NEW.account_id;
    IF NEW.currency <> acct_ccy THEN
        RAISE EXCEPTION 'entry currency % does not match account % currency %',
            NEW.currency, NEW.account_id, acct_ccy;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entry_currency
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION assert_entry_currency();
```

**Tests** (direct SQL via `JdbcClient`, none `@Transactional`):
- `unbalancedTransactionRejected` — I1 fires at commit
- `balancedTransactionAccepted` — I1 does not false-positive
- `entryUpdateRejected`, `entryDeleteRejected` — I5
- `transactionUpdateRejected`, `transactionDeleteRejected` — I5
- `negativeBalanceRejected` — I4
- `negativeBalanceAllowedOnEquityAccount` — I4 respects `allow_negative`
- `zeroAmountRejected`, `oversizedAmountRejected`
- `mismatchedCurrencyRejected` — I7
- `doubleReversalIndexRejected` — the partial unique index

### Break proof (required)
For each of I1, I5, I7: drop the trigger, run the corresponding test, paste the failing output, restore the trigger, paste the passing output.

### Pitfalls
- **Never use `CREATE RULE ... DO INSTEAD NOTHING`.** It swallows the statement silently, so the caller believes it succeeded — the worst failure mode in a ledger. Use a trigger that RAISEs.
- The I1 trigger must be `CREATE CONSTRAINT TRIGGER` + `AFTER INSERT` + `FOR EACH ROW` + `DEFERRABLE INITIALLY DEFERRED`. A plain `CREATE TRIGGER` cannot be deferrable.
- A deferred trigger only fires on actual commit. Tests annotated `@Transactional` roll back, so it never runs. These tests must **not** be `@Transactional`.
- The I5 trigger fires `BEFORE`, so it blocks the mutation rather than reporting it afterward.
- `assert_entry_currency` does a lookup per row. Acceptable here; note it in Phase 5 if it shows up in profiling.

### Exit criteria
- [ ] All eleven schema tests pass
- [ ] Break proof supplied for I1, I5, I7 with pasted failing and passing output
- [ ] No `version` column anywhere
- [ ] Migrations apply cleanly from scratch; none were edited after creation
- [ ] Report contains the full SQL of every trigger

---

## PHASE 1b — Domain, service, API
**Branch:** `phase-1b-service`

### Goal
Transfers work end to end through HTTP. Not yet idempotent, not yet hardened for concurrency. All validation rules V1–V4 enforced and tested.

### Deliverables

**Domain:**
- `Money` — value object wrapping `long`, using `addExact`/`subtractExact`, with `MAX_AMOUNT = 10_000_000_000L`
- `AccountType`, `TxType` enums
- Jackson configured to **reject** decimal input for amount fields (`DeserializationFeature.ACCEPT_FLOAT_AS_INT` disabled)

**Store:** `AccountRepository`, `TransactionRepository`, `EntryRepository` — `JdbcClient`, explicit SQL

**Service:**
- `LedgerService.transfer(...)` — single `@Transactional` block, no locking yet
- `LedgerService.fund(...)` — EQUITY → LIABILITY
- Validation V1–V3 enforced before any database write

**API:**
- `POST /v1/accounts`, `GET /v1/accounts/{publicId}`
- `GET /v1/accounts/{publicId}/entries` — cursor pagination (`?after=<entryId>&limit=50`)
- `POST /v1/transfers` (no idempotency yet), `GET /v1/transfers/{publicId}`
- RFC 7807 Problem Details error bodies
- `X-Client-Id` header required on mutating endpoints (V5); value is recorded, not authenticated

**Tests:**
- `negativeAmountRejected` — **V1, the critical one.** Assert the response is 422 *and* that no ledger rows were written *and* that neither balance changed.
- `zeroAmountRejected`, `oversizedAmountRejected` — V1, V2
- `selfTransferRejected` — V3, 422, no rows written
- `decimalAmountRejected` — V4, 400
- `missingClientIdRejected` — V5, 400
- `transferHappyPath`, `fundingHappyPath`
- `insufficientFundsRejected`
- `entriesPaginationStable` — cursor pagination returns each entry exactly once
- jqwik property: 1000 random transfers → `SUM(amount) = 0` (I2)
- jqwik property: every account's `balance = SUM(entries.amount)` (I3, single-threaded)

### Pitfalls
- **V1 is not optional and is not covered by any invariant.** With `amount = -100`, the source account's balance *increases* and the destination's decreases — and because the receiving side has no balance check, the ledger stays balanced and non-negative while money is created. Write the test first, then the validation.
- V3 is not merely cosmetic. With `from == to`, ordered locking locks the same row twice, the two UPDATEs cancel out, and the account accumulates paired `-x` / `+x` entries. Invariants all pass; the data is garbage.
- Keep the `Money` ↔ `long` conversion in exactly one place.
- Cursor pagination, not offset. Offset degrades to O(n) on large accounts.

### Exit criteria
- [ ] All validation tests pass, including `negativeAmountRejected` and `selfTransferRejected`
- [ ] Both property tests pass over ≥1000 random sequences
- [ ] An account can be created, funded, and transferred from via `curl`
- [ ] Rejected requests write **zero** rows — asserted, not assumed
- [ ] Report includes the validation code and the two property tests

---

## PHASE 2 — Idempotency and concurrency
**Branch:** `phase-2-concurrency` · **BREAK PROOF REQUIRED**

### Goal
The most critical phase. The system is correct under concurrent load. I6 is guaranteed.

### Deliverables

**`V3__idempotency.sql`:**
```sql
CREATE TABLE idempotency_keys (
    id             BIGSERIAL   PRIMARY KEY,
    client_id      TEXT        NOT NULL,
    idem_key       TEXT        NOT NULL,
    request_hash   TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    response_code  INT,
    response_body  JSONB,
    transaction_id BIGINT      REFERENCES ledger_transactions(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_client_key UNIQUE (client_id, idem_key),
    CONSTRAINT valid_status CHECK (status IN ('IN_PROGRESS','COMPLETED'))
);
CREATE INDEX idx_idem_expiry ON idempotency_keys(expires_at);
```

**Code:**
- `Idempotency-Key` and `X-Client-Id` required on all mutating endpoints
- `request_hash` = SHA-256 over `method + path + canonicalized JSON body`. Including method and path is mandatory — otherwise one key collides across transfer and reversal.
- Protocol:
  - `INSERT ... ON CONFLICT DO NOTHING RETURNING id`
  - Row returned → the key is ours, proceed
  - No row + `COMPLETED` + same hash → return stored response, `200`
  - No row + `COMPLETED` + different hash → `422 idempotency_key_reuse`
  - No row + `IN_PROGRESS` → `409 request_in_progress`
- Deterministic lock order: `SELECT ... FOR UPDATE` on accounts in ascending internal `id` order
- Conditional balance UPDATE:
  ```sql
  UPDATE accounts SET balance = balance - :amount
  WHERE id = :from AND (allow_negative OR balance >= :amount)
  ```
  Zero affected rows → `422 insufficient_funds`, rollback
- The idempotency record commits in the **same transaction** as the ledger write

**Tests** (none `@Transactional`, all starting simultaneously via `CountDownLatch`):

| Test | Setup | Expected |
|---|---|---|
| `duplicateIdempotencyKey` | 100 threads, same key + same body | Exactly 1 `ledger_transaction`; one 201, rest 200 or 409 |
| `sameKeyDifferentBody` | Same key, different amount | 422 |
| `sameKeyDifferentEndpoint` | Same key on transfer then reversal | 422, not a false idempotent hit |
| `concurrentTransfersNoLostUpdate` | 200 concurrent A→B of 100 kuruş | A = start−20000, B = start+20000 |
| `bidirectionalNoDeadlock` | 50 threads A→B plus 50 threads B→A | Zero deadlock exceptions, all complete |
| `overdraftUnderRace` | Balance 1000, 50 threads × 100 kuruş | Exactly 10 succeed, 40 rejected, final balance 0 |
| `concurrentNegativeAmountRejected` | 50 threads, amount = −100 | All 422, balances unchanged |

### Break proof (required)
- Replace ordered locking with fixed-order locking (always lock `from` then `to`), run `bidirectionalNoDeadlock`, paste the deadlock failure, restore, paste the pass.
- Remove the `ON CONFLICT` guard (plain INSERT with catch), run `duplicateIdempotencyKey`, paste the failure showing more than one transaction, restore, paste the pass.

### Pitfalls
- **`ON CONFLICT DO NOTHING` with `RETURNING` yields an empty result on conflict**, it does not throw. Interpret the empty result correctly.
- Lock ordering must use the internal `id`, not the `public_id` UUID.
- `bidirectionalNoDeadlock` fails **intermittently** when ordering is wrong. Run it at least 5 times; one deadlock means it is broken.
- Build the pool with `Executors.newFixedThreadPool(N)` where `N` equals thread count. A smaller pool falsely reduces concurrency.
- No test transaction here, so clean data explicitly per test.
- **Do not add a deadlock retry loop.** With correct ordering it is unnecessary, and it would mask broken ordering. The deadlock metric arrives in Phase 4 and must stay at zero.

### Exit criteria
- [ ] All seven concurrency tests pass
- [ ] `bidirectionalNoDeadlock` shows zero deadlocks across 5 consecutive runs
- [ ] Both break proofs supplied with pasted output
- [ ] Report shows the code proving the idempotency record and ledger write share one transaction
- [ ] Insufficient-funds check is **not** an application-level `if` — report includes the SQL
- [ ] No deadlock retry loop anywhere

---

## PHASE 3 — Outbox and event publishing
**Branch:** `phase-3-outbox` · **BREAK PROOF REQUIRED**

### Goal
Ledger writes and event publishing are atomic. No lost events, no double processing.

### Deliverables

**`V4__outbox.sql`:**
- `outbox_events` — `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload` JSONB, `created_at`, `published_at`, `attempts`, `last_error`
- Partial index: `CREATE INDEX ... ON outbox_events(id) WHERE published_at IS NULL`
- `consumed_events` — PRIMARY KEY `(consumer_group, event_id)`

**Code:**
- Insert into `outbox_events` inside the transfer transaction
- `OutboxRelay` — scheduled (~200 ms), separate transaction:
  ```sql
  SELECT * FROM outbox_events WHERE published_at IS NULL
  ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
  ```
  → publish to Kafka → set `published_at = now()`
- Kafka producer: `acks=all`, `enable.idempotence=true`, partition key = `aggregate_id`
- Consumer: `AccountActivityProjection`
- Consumer dedup: processing and the `consumed_events` insert share **one transaction**; on conflict, skip

**Tests:**
- `outboxWrittenAtomically`, `outboxNotWrittenOnRollback`
- `relayPublishesAndMarks`, `relayRetriesUnpublished`
- `consumerReplayIdempotent`
- `perAccountOrdering`

### Break proof (required)
Disable the `consumed_events` insert, run `consumerReplayIdempotent`, paste the failure showing double application, restore, paste the pass.

### Pitfalls
- The relay does **not** run inside the transfer transaction.
- A crash between publishing and marking republishes the event. This is the **accepted** at-least-once behavior, absorbed by dedup. Do not attempt exactly-once.
- `SKIP LOCKED` breaks global ordering. A single relay instance plus partition key `aggregate_id` preserves per-account ordering. Document in ADR-003.
- **Do not use a high-water-mark cursor (`id > last_seen`).** `BIGSERIAL` values can be assigned in a different order than commits occur; an event committed behind an advanced cursor is skipped forever. The `published_at IS NULL` marker makes this structurally impossible.
- Kafka testcontainer in KRaft mode. Tag these tests `@Tag("kafka")`, separate CI job.
- Commit the consumer offset only **after** the dedup transaction commits.

### Exit criteria
- [ ] All six outbox/consumer tests pass
- [ ] Break proof supplied for consumer dedup
- [ ] Zero event loss under a process-kill scenario
- [ ] `SKIP LOCKED` ordering trade-off explained in the report
- [ ] Kafka tests in a separate CI job; total CI duration recorded

---

## PHASE 4 — Reversal, reconciliation, observability
**Branch:** `phase-4-recon-observability`

### Goal
I3 is continuously monitored. The system is observable from the inside. Nothing grows without bound.

### Deliverables

**Reversal:**
- `POST /v1/transfers/{publicId}/reversals` — both headers required
- New `ledger_transaction` (`tx_type = REVERSAL`) with sign-flipped copies of the original entries
- Originals never touched; `uq_single_reversal` prevents double reversal
- Accounts locked in ascending `id` order

**Reconciliation** — scheduled (5 min), batched:
```sql
SELECT a.id, a.balance, COALESCE(SUM(e.amount),0) AS computed
FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
WHERE a.id BETWEEN :lo AND :hi
GROUP BY a.id, a.balance
HAVING a.balance <> COALESCE(SUM(e.amount),0)
```
On drift: increment metric, log account ids, **do not correct**.
Global check: `SELECT SUM(amount) FROM ledger_entries` must be 0.

**Retention jobs** — without these, two tables grow forever:
- `IdempotencyKeyCleanupJob` — daily, deletes rows where `expires_at < now()`. Batched (`DELETE ... WHERE id IN (SELECT id ... LIMIT 10000)` in a loop) so it never takes a long lock.
- `OutboxArchivalJob` — daily, deletes `outbox_events` where `published_at < now() - interval '7 days'`. Same batching. The 7-day window is a deliberate choice: long enough to debug a delivery problem, short enough to bound the table.
- Both emit a metric for rows removed.

**Metrics** (Micrometer):
- `ledger_transfer_duration_seconds` (histogram, tagged `result`)
- `ledger_transfer_total{result}` — success / insufficient_funds / conflict / invalid
- `ledger_idempotency_hit_total`
- `ledger_deadlock_retry_total` — must stay at zero
- `ledger_balance_drift_total` — alarm source
- `ledger_outbox_lag_seconds`, `ledger_outbox_pending` (gauges)
- `ledger_cleanup_rows_deleted_total{job}`

**Tracing:** Micrometer Tracing + OpenTelemetry, context propagated through Kafka headers.
**Stack:** add Prometheus, Grafana, Tempo to compose. Dashboard as JSON under `ops/grafana/`.

**Tests:**
- `reversalCreatesCompensatingEntries`
- `doubleReversalRejected` — two concurrent reversals, one succeeds
- `reversalFailsOnInsufficientFunds` — rejected if money was spent (**correct** behavior)
- `reconciliationDetectsDrift`, `reconciliationDoesNotAutoFix`
- `expiredIdempotencyKeysRemoved`, `publishedOutboxEventsArchived`
- `unpublishedOutboxEventsNeverArchived` — the archival job must never delete an event with `published_at IS NULL`, regardless of age

### Pitfalls
- A reversal never `UPDATE`s the original.
- Reconciliation must be batched, or large datasets cause long locks and heavy I/O.
- Resist auto-correcting drift. Silent correction hides root cause.
- The archival job filtering on `created_at` instead of `published_at` would delete unpublished events. Filter on `published_at`, and test the negative case.
- If trace context does not cross Kafka, producer and consumer spans land in separate traces. Wire `KafkaTemplate` to the `ObservationRegistry`. Verify with a screenshot.
- Commit the Grafana dashboard JSON; do not build it by hand in the UI.

### Exit criteria
- [ ] All eight reversal/reconciliation/cleanup tests pass
- [ ] `ledger_balance_drift_total` increments on a corrupted balance
- [ ] `unpublishedOutboxEventsNeverArchived` passes
- [ ] Screenshot of a **single** trace spanning HTTP through to the consumer
- [ ] Grafana dashboard JSON committed
- [ ] `ledger_deadlock_retry_total` = 0

---

## PHASE 5 — Load testing
**Branch:** `phase-5-load`

### Goal
Establish the system's ceiling and the cause of that ceiling, **with numbers**.

### Deliverables

**k6 scenarios** (`load/`):
- **Scenario U (uniform):** random transfers across 10,000 accounts, minimal contention
- **Scenario H (hot account):** every transfer writes a fee to the same REVENUE account, maximal contention
- Ramp: 10 → 50 → 100 → 200 → 400 VUs, 2 min per step, after a 1-minute warmup (JIT)
- Account seeding script

**Additional run for ADR-004:** the same scenarios under `SERIALIZABLE` with a retry loop, compared against READ COMMITTED with ordered locking.

**Measurements:**
- TPS and p50 / p95 / p99 per step
- Saturation point: where TPS stops rising and latency blows up
- Database side: `pg_stat_activity` wait events, `pg_stat_database.deadlocks`, tuple contention
- HikariCP pool saturation
- `ledger_outbox_lag_seconds` under load

**Output:** `load/RESULTS.md` with raw numbers and charts in `load/charts/`

### Pitfalls
- Warmup is mandatory; the first 30–60 seconds are JIT noise.
- Running k6 on the application's machine causes CPU competition. State it if it applies.
- The connection pool may itself be the ceiling — that is a finding, not something to hide.
- Scenario H being slower than U is the **expected** outcome and the project's central finding, not a bug.
- If results are lower than expected, do not try to "improve" them. Phase 5 measures; it does not optimize.

### Exit criteria
- [ ] Complete result sets for both scenarios, step by step
- [ ] Saturation point identified and its **cause** explained
- [ ] U vs H difference charted
- [ ] SERIALIZABLE comparison quantified
- [ ] `load/RESULTS.md` written

---

## PHASE 6 — Packaging
**Branch:** `phase-6-docs`

### Goal
Someone who has never seen the project can read the README, run it in 10 minutes, and understand what was built.

### Deliverables

**README.md:**
- One paragraph: what this is, why it exists
- Architecture diagram (Mermaid, versioned as text)
- The eight invariants and seven validation rules, each with how it is enforced
- Quick start: `docker compose up` → a single `curl` transfer
- API reference
- Test strategy: which test proves which invariant, plus the break-proof method
- k6 results: charts, saturation point, interpretation
- Trace screenshot
- Known limits and deliberate scope exclusions, stated honestly
- CI badge

**ADRs** (`docs/adr/`, each: Context → Decision → Consequences → Rejected alternatives):

| ADR | Topic |
|---|---|
| 001 | Signed delta vs. separate debit/credit columns |
| 002 | Materialized balance + reconciliation vs. summing on read |
| 003 | Transactional outbox vs. CDC vs. dual write |
| 004 | READ COMMITTED + ordered locking vs. SERIALIZABLE + retry (**with Phase 5 numbers**) |
| 005 | Idempotency record in the same transaction as the ledger write |
| 006 | Hot account contention: measured, sharded counter not implemented |
| 007 | BIGINT minor units vs. NUMERIC vs. BigDecimal |
| 008 | Cursor pagination vs. offset |
| 009 | Why validation rules V1–V3 are not expressible as ledger invariants |

**Optional — mutation testing.** Add PIT (`pitest-maven`), run against `domain`, `service`, and `store`, report the mutation score in the README. This turns "my tests protect the invariants" into a number. Budget 1–2 days. **Drop this first if the schedule slips**; it is the only optional item in the plan.

**Cleanup:** remove dead code, unused dependencies, `TODO`s. Write `docs/future.md`.

### Pitfalls
- An ADR without the **rejected alternative and why** is worthless. That is where the value lives.
- ADR-009 is the most interesting one to a reviewer: it explains a class of bug that formal invariants cannot catch.
- Do not write "production-ready". It is not, and it does not need to be.
- Do not dress up results. A measured and explained limit beats an invented success.

### Exit criteria
- [ ] README complete, every section filled
- [ ] 9 ADRs written, each including rejected alternatives
- [ ] `docker compose up` followed by a single `curl` performs a transfer
- [ ] CI green (including the rule guard), badge in README
- [ ] No `TODO`s, no dead code, no AI tool references

---

## Definition of done

- [ ] Each of the eight invariants is protected by at least one automated test
- [ ] Each of the seven validation rules is protected by at least one automated test
- [ ] Break proofs recorded for I1, I5, I7, I8, lock ordering, idempotency, and consumer dedup
- [ ] `duplicateIdempotencyKey` passes with 100 threads
- [ ] `bidirectionalNoDeadlock` shows zero deadlocks across 5 consecutive runs
- [ ] Property tests verify global balance across ≥1000 random sequences
- [ ] Zero event loss under a process-kill scenario
- [ ] Neither `idempotency_keys` nor `outbox_events` grows without bound
- [ ] README contains real k6 numbers: TPS, p95, p99, saturation point and cause
- [ ] U vs H difference charted and explained
- [ ] Screenshot of a single trace spanning HTTP through to the consumer
- [ ] 9 ADRs written with rejected alternatives
- [ ] `docker compose up` + one `curl` performs a transfer
- [ ] CI green including `ci/check-rules.sh`

---

## PROGRESS.md template

Create this in Phase 0 and update it after every meaningful step.

```markdown
# Progress

**Current phase:** 0 — Skeleton
**Branch:** phase-0-skeleton
**Last updated:** YYYY-MM-DD HH:MM

## Done in this phase
- [x] step — commit sha

## In progress
- step, and exactly where it stands

## Blocked / open questions
- question, and what it is blocking

## Next step
- the single next action
```
