# CLAUDE.md — Ledger Payment Core

Read this file at the start of every session. These rules have no exceptions.

---

## What this project is

A payment core built on a double-entry accounting ledger with idempotent transfers. This is not a production system; it is an engineering project where correctness guarantees are **proven**. The deliverable is not working code — it is code that is **tested and measured**.

Single currency: TRY. All amounts are carried as minor units (kuruş) in `long`.

---

## The Eight Invariants

These are the reason the system exists. Every change must demonstrate it preserves them.

| # | Invariant | Enforcement |
|---|---|---|
| I1 | Entries of a transaction sum to zero | Deferred constraint trigger |
| I2 | All entries in the system sum to zero | Reconciliation job + property test |
| I3 | An account's materialized balance equals the sum of its entries | Reconciliation job + metric alarm |
| I4 | An account with `allow_negative = false` cannot go negative | CHECK constraint + conditional UPDATE |
| I5 | Entries are immutable (append-only) | Trigger that RAISEs + DB role grants |
| I6 | A (client_id, idempotency_key) pair maps to at most one transaction | UNIQUE constraint |
| I7 | An entry's currency matches its account's currency | BEFORE INSERT trigger |
| I8 | All entries of a transaction share a single currency | Deferred constraint trigger |

Enforcing an invariant "in the application layer" is **forbidden**. Every invariant must have a database-level defense.

---

## Request validation rules

The invariants above do **not** cover these. Each is a separate, explicitly tested API-level rule. Skipping any of them allows money to be created out of nothing while every invariant still holds.

| # | Rule | Response on violation |
|---|---|---|
| V1 | Transfer amount must be strictly positive | `422 invalid_amount` |
| V2 | Transfer amount must not exceed `10_000_000_000` (100M TRY in kuruş) | `422 amount_too_large` |
| V3 | `from_account` must differ from `to_account` | `422 self_transfer` |
| V4 | Amount must be an integer; decimals are rejected at deserialization | `400` |
| V5 | `X-Client-Id` header is required on all mutating endpoints | `400 missing_client_id` |
| V6 | `Idempotency-Key` header is required on all mutating endpoints | `400 missing_idempotency_key` |
| V7 | `from_account` and `to_account` must carry the same currency | `422 currency_mismatch` |

**V1 is the most dangerous omission.** A negative amount inverts the transfer direction while bypassing the balance check on the receiving side. Every invariant still passes; money is created. There must be an explicit test named `negativeAmountRejected` and another named `selfTransferRejected`.

V7 is the same class of hole. Entries of -1000 TRY and +1000 USD sum to zero and each matches its own account's currency, so I1, I2, I3 and I7 all pass while money is created. I8 is the database-level defense against it; V7 exists so the request is refused with a clean 422 before any balance moves, rather than blowing up at commit. There must be an explicit test named `crossCurrencyTransferRejected`.

---

## Absolute rules

### Money
- Amounts are **never** `double`, `float`, or `BigDecimal`. Always `long`, minor units.
- The API does not accept decimal amounts. A body containing `1250.00` returns `400`.
- Use `Math.addExact` / `Math.subtractExact` for amount arithmetic. Silent overflow is forbidden.

### Database
- No ORM. `JdbcClient` with explicit SQL. No JPA, no Hibernate, no dirty checking.
- SQL stays visible in application code. It must be readable which query takes which lock.
- Every schema change is a Flyway migration. An existing migration file is **never** edited; add a new one.
- Any operation touching two accounts locks them in **ascending internal `id` order**. No exceptions.
- Insufficient-funds checks live in the `WHERE` clause of a conditional `UPDATE`, never in an application-level `if`.
- No optimistic-locking `version` column. Pessimistic ordered locking plus conditional UPDATE is the concurrency strategy; a version column alongside it is dead weight and misleads reviewers.
- `allow_negative` is a generated column derived from `account_type`; only EQUITY may go negative. It is not a request field, and the application never writes it — the database refuses a supplied value.

### Client identity
- `client_id` comes from the `X-Client-Id` request header. It is **not authenticated** — authentication is out of scope. It exists solely to scope idempotency keys.
- `request_hash` = SHA-256 over `HTTP method + request path + canonicalized JSON body`. The method and path **must** be included, otherwise the same key could collide across a transfer and a reversal.
- Canonicalization: keys sorted, whitespace stripped. Two semantically identical bodies must produce the same hash.

### Testing
- Testcontainers with **real** PostgreSQL and **real** Kafka. H2, embedded Kafka, and mocked databases are forbidden.
- Concurrency tests must **not** be `@Transactional` — a test transaction hides real concurrency.
- Concurrency tests use a `CountDownLatch` to achieve genuine simultaneous start. Merely starting threads is not enough.
- A test that "passes sometimes" is broken. Fix it or delete it; never tolerate flakiness.

### Break proof

A green test proves nothing on its own — an empty test is also green. For every phase marked **BREAK PROOF REQUIRED**, the report must show that the test genuinely detects the failure it claims to guard against:

1. Deliberately break the mechanism (drop the trigger, replace ordered locking with fixed-order locking, disable the dedup insert).
2. Run the test. **Paste the failing output into the report.**
3. Restore the mechanism.
4. Run the test again. **Paste the passing output.**

A report without both outputs is incomplete and the phase will be rejected.

### Immutability
- No UPDATE or DELETE on `ledger_entries` or `ledger_transactions`. Corrections are compensating entries.
- If reconciliation finds drift it **does not auto-correct**. It raises an alarm, logs, and stops.

### Git

**Style.** Commit messages in English, imperative mood, first word capitalized, no trailing period:
```
Add idempotency key table
Enforce balanced transactions with a deferred constraint trigger
Reject self-transfers before any database write
```
The subject line stands alone under ~72 characters. Add a body only when the *why* is not obvious from the diff.

**Granularity.** Each phase produces **2–6 commits**. Exceed six only when the phase genuinely contains more separable units; never split artificially to reach a count, and never squash a phase into one commit.

Each commit is one logical unit that leaves the build green. Concretely, a phase usually splits along these lines:
- One commit per migration plus the code that first uses it
- One commit per independent mechanism (locking, idempotency protocol, relay, consumer)
- Tests land **with** the code they test, not as a separate "add tests" commit — a commit whose diff is only tests is a sign the earlier commit was incomplete
- Refactors and formatting go in their own commit, never mixed with behavior changes

A commit named `Fix stuff`, `WIP`, or `Phase 2` is unacceptable. So is a single commit containing an entire phase — the history is part of the deliverable and a reviewer reads it.

**Other rules.**
- **No AI tool references** in commit messages or in committed content. No `Co-authored-by`, no "generated by".
- Each phase on its own branch: `phase-0-skeleton`, `phase-1a-schema`, and so on.
- **Never merge into `main` yourself.** A phase branch is merged only after an independent audit passes, and the merge is performed by the user. Finishing a phase means the branch is pushed and the report is written — nothing more.
- Never rewrite pushed history.

### Approved dependency exceptions

These were flagged and approved once; do not re-raise them:
- `org.springframework.boot:spring-boot-testcontainers` — `@ServiceConnection` lives here
- `org.springframework.boot:spring-boot-flyway` — Spring Boot 4 moved Flyway autoconfiguration out of `spring-boot-starter-jdbc`
- `org.springframework.kafka:spring-kafka` — the producer and the `@KafkaListener` container (Phase 3)
- `org.springframework.boot:spring-boot-kafka` — Spring Boot 4 moved the Kafka autoconfiguration and its Testcontainers service connection into this module, the same split as `spring-boot-flyway`
- `org.testcontainers:testcontainers-kafka` — the KRaft-mode broker the Phase 3 tests run against

Any further dependency beyond a phase's list still requires approval via OPEN QUESTIONS.

### Code
- Code, comments, log messages, variable names, documentation, and reports: **English**.
- Comments explain **why** only. Delete any comment that explains **what**.
- No dead code, no unused imports, no `TODO`. If it will not be done, write it into `docs/future.md`.
- No column, field, or dependency that nothing reads. Unused structure is worse than absent structure.

---

## Scope lock

The following are **out of scope**. If an idea comes up, write it to `docs/future.md` and do not implement it:

- Multiple currencies, FX conversion
- Real payment rails (cards, FAST, EFT)
- KYC, authentication, user management, sessions, JWT
- Frontend / UI
- Multi-tenancy
- Sharding, multi-region, read replicas
- Horizontally scaled relay

Scope creep is the **primary failure risk** for this project.

---

## Phase discipline

- Implement **only the phase you were given**. Do not do work belonging to a later phase "so it's ready".
- Do not add dependencies that are not listed in the phase definition.
- A phase is not complete until every exit criterion is met.
- When something is ambiguous, **do not guess**. Record it in the report under OPEN QUESTIONS and leave that part unimplemented.
- Update `PROGRESS.md` after every meaningful step, not only at the end. If context is lost mid-phase, `PROGRESS.md` is the only way to resume without redoing work.

---

## Report format

At the end of every phase, produce a report in exactly this structure:

```
## PHASE N REPORT

### What was done
- (bullet points, with file paths)

### Files created/modified
- path — one-line description

### Exit criteria
- [x] criterion — how it was verified
- [ ] criterion — why it was not met

### Test results
(summary of `mvn verify`: test count, duration, passed/failed)

### Commits
(output of `git log --oneline main..HEAD` — 2–6 commits expected)

### Break proof
(only for phases marked BREAK PROOF REQUIRED)
- Mechanism broken: what was disabled and how
- Failing output: (pasted)
- Mechanism restored, passing output: (pasted)

### Critical code
(the actual code for locking, SQL, triggers, validation — for review)

### Deviations
(anything that departed from the plan, and why)

### OPEN QUESTIONS
(decisions that could not be made and need user approval)

### Notes for the next phase
```

Do not embellish the report. Never describe something as working when it is not. If a criterion was not met, say so plainly and explain why.
