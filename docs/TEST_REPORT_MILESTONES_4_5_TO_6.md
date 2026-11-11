# Executed test report — Milestones 4.5 to 6

Executed on 2026-11-11 against Java 21, Spring Boot 4.1.2, PostgreSQL 17 Testcontainers, and Flyway V1–V8.

## Final result

| Gate | Suites | Tests | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Phase A — Google authentication | 9 | 22 | 22 | 0 | 0 | 0 |
| Phase B — transactional confirmation | 10 | 26 | 26 | 0 | 0 | 0 |
| Phase C — final lifecycle gate | 11 | 29 | 29 | 0 | 0 | 0 |

The final uncached command also built the executable Boot jar successfully. Every PostgreSQL integration context migrated an empty database through V1–V8 and Hibernate validated the resulting schema.

## Commands actually executed

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --tests "*GoogleAuthenticationPostgresIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test --tests "*BookingConfirmationPostgresIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test --tests "*BookingLifecyclePostgresIntegrationTest" --no-daemon --console=plain
.\gradlew.bat test --tests "*BookingConfirmationPostgresIntegrationTest" --tests "*BookingLifecyclePostgresIntegrationTest" --no-daemon --console=plain
docker compose config --quiet
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:8082
```

OpenAPI was exported from the running V8 application using `scripts/export-openapi.ps1`. Both Postman JSON files were parsed successfully with PowerShell `ConvertFrom-Json`.

## Phase A coverage

- Deterministic test-verifier coverage for new Google customer/provider registration, linked login, explicit same-email linking, invalid/unverified identity handling, role restrictions, platform JWT/refresh issuance, account state, and marketplace-verification initialization.
- Native password registration/login, BCrypt behavior, refresh rotation, and logout remained green.
- Google-only accounts do not receive fake passwords and cannot authenticate through native password login.
- Google was not tested against the real production identity service. No production Google credentials were configured or committed.

## Phase B coverage

- Valid Job Posting and Direct Invitation Proposal confirmation.
- Customer/provider account and marketplace-eligibility rechecks, request/Proposal state denial, ownership denial, category relationship, declared weekly availability, invalid intervals, and protected-data authorization.
- One transaction creates the Booking, accepts one Proposal, rejects competing pending Proposals, selects the provider, and writes activity.
- Same-Proposal retry returns one Booking; a losing Proposal cannot also be accepted.
- Half-open conflicts reject overlap and allow adjacent intervals.
- Marketplace discovery subtracts confirmed reservations through the Booking application boundary.
- A real two-thread PostgreSQL race produced exactly one success, one schedule conflict, and one blocking Booking.

## Phase C coverage

- Selected-provider start and completion report; booking-customer completion confirmation.
- Wrong-role/unrelated-principal denial and invalid transition conflicts.
- Idempotent target-state retries without duplicate status-history rows.
- Customer pre-service cancellation retains the record, actor/timestamp/reason and becomes non-blocking for both confirmation and discovery.
- Cancellation after service start is rejected.
- Completed/cancelled status filtering, pagination envelope, participant privacy, and continued visibility after marketplace eligibility later changes.
- No messaging, notification, rating/review, service-payment, wallet, escrow, or payout feature was added.

## Failures encountered and corrected

- Initial pre-flight failed because Docker Desktop was not running; after Docker started, the unchanged baseline passed 18/18 tests.
- Early Booking fixture runs exposed a real `TIME`/UTC materialization error (`08:00` loaded as `11:00`). Availability `LocalTime` fields now use Hibernate's native `LOCAL_TIME` JDBC type; schedule tests then passed.
- The first Phase B full gate found two stale Milestone-4 absence assertions. They were replaced with Milestone-5 architecture and OpenAPI assertions, then the complete gate passed.
- No implementation failure was hidden or bypassed with skipped tests.

## Assumptions and unresolved policy

- Proposal currency is ETB because the existing Proposal model has a price but no per-Proposal currency field.
- Weekly civil-time availability is interpreted in `Africa/Addis_Ababa`; persisted Booking timestamps are instants.
- Google linking requires the verified Google email to match the platform account email.
- Customer cancellation is allowed only from `CONFIRMED`. Provider-initiated cancellation is deferred because no approved responsibility/refund/escalation policy is defined.
- `UNDER_REVIEW` is retained as a lifecycle boundary, but no dispute-opening endpoint was invented because dispute category, intake, and resolution policy are underspecified.
