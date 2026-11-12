# Final backend test report

Executed locally on 2026-11-12 from `codex/milestones-7-8-release` after Milestones 7–8 and release hardening.

## Verified environment

- Java: Eclipse Temurin-compatible Java 21 toolchain; live process reported 21.0.12.1
- Spring Boot: 4.1.2
- Database: PostgreSQL 17.11 for live startup and PostgreSQL 17 Testcontainers for automated integration tests
- Schema: immutable Flyway path V1–V11; Hibernate `ddl-auto=validate`; H2 was not used
- AI: Spring AI 2.0.2, disabled by default; automated tests use a deterministic project-owned adapter and make no live model call

## Complete automated regression

Command:

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
```

Result: **PASS** in 3m 23s. JUnit XML contained **13 suites / 35 tests**: 35 passed, 0 failures, 0 errors, 0 skipped. Aggregate test time was 126.858 seconds.

Coverage includes native signup/login/session rotation/logout, deterministic Google authentication/linking, profiles/verification/eligibility, categories/discovery/requests/invitations, Posting Fee callbacks and proposals, transactional Booking confirmation/lifecycle/history, communication/notifications/blocking/reporting/support/preferences, reciprocal feedback, admin reporting/audit/account status, and advisory AI behavior.

## Concurrency and idempotency

- Overlapping provider Booking confirmations execute concurrently and serialize on provider schedule state; exactly one conflicting confirmation succeeds.
- Adjacent non-overlapping Booking intervals succeed, and retrying the accepted Proposal returns the same Booking.
- Concurrent verified Posting Fee callbacks publish one job and leave exactly one successful transaction.
- Booking lifecycle retries do not append duplicate status history; refresh tokens rotate once and revoked/replayed tokens fail.

## Performance hardening

- Booking lists now filter and paginate in PostgreSQL rather than loading all participant history into application memory.
- Discovery batches account status, approval, availability, categories, assignments, and rating aggregates instead of per-provider lookups.
- Job-feed provider eligibility is computed once per request and page attachments are batch-loaded.
- Message pagination and feedback/rating aggregation were inspected and already use bounded database queries.
- Admin dashboard uses bounded aggregate queries and audit pagination; no cross-module mutation was added.
- V11 adds focused Booking participant/status/order indexes and a deterministic partial open-job ordering index.

## Security regression

- Role/ownership tests cover private profiles, exact location/phone, verification evidence, protected attachments, messages, support cases, feedback, and administrator APIs.
- Public registration cannot assign `ADMIN`; account status changes invalidate credential versions and revoke refresh sessions.
- Production configuration disables local administrator bootstrap and the deterministic payment adapter; Spring AI is off by default and keys/model internals are not logged or returned.
- Repository scan found no committed private keys, debug output, TODO/FIXME placeholders, or hard-coded application secrets.
- CI remains Java 21 with the complete Testcontainers suite followed by `bootJar`; it was not weakened.

## Live migration, smoke, and contracts

- Compose PostgreSQL health: **PASS**.
- Live startup: **PASS**; Flyway validated 11 migrations, applied V11 over V10, and Hibernate validation completed without manual SQL.
- Smoke test: **PASS** for health, customer signup, `/me`, refresh rotation, logout, refresh revocation, provider signup/onboarding, and unauthenticated rejection.
- Docker Compose validation: **PASS**.
- Regenerated OpenAPI: **86 paths / 99 operations**.
- Postman: valid JSON, **21 folders / 73 requests / 25 environment variables**.

## Packaging

Command:

```powershell
.\gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain
```

Result: **PASS** in 3m 27s. All 8 tasks executed, the executable Boot JAR was produced, and the repeated JUnit XML contained 13 suites / 35 tests: 35 passed, 0 failures, 0 errors, 0 skipped (130.236 seconds aggregate test time).

## Remaining technical debt

- The provider Job Posting feed still evaluates all currently open jobs in memory after loading them in deterministic order. Its per-row N+1 behavior is removed, but a future high-volume deployment should move category/area eligibility into a PostgreSQL projection with database pagination.
- Booking page enrichment still performs bounded per-item request snapshot/history/contact lookups. Because the page is capped at 100 this no longer scales with total history; a purpose-built list projection is the next optimization if measurements justify it.
- No production Posting Fee gateway or external notification delivery channel is bundled. These are explicit integrations, not incomplete domain behavior.
