# Milestone 7 test report

Executed locally on 2026-11-12 from `codex/milestones-7-8-release` after the Milestone 7 implementation.

## Environment

- Java toolchain: 21
- Spring Boot: 4.1.2
- PostgreSQL/Testcontainers image: `postgres:17-alpine`
- Schema migration range: V1–V9
- Hibernate schema mode: validation
- H2: not used

## Executed verification

`./gradlew.bat clean test --no-daemon --console=plain`

- Suites: 12
- Tests: 32
- Passed: 32
- Failed: 0
- Errors: 0
- Skipped: 0
- Result: `BUILD SUCCESSFUL`

`CommunicationSupportPostgresIntegrationTest` contributes three PostgreSQL HTTP acceptance tests covering:

- relationship-backed conversation creation and participant-only access;
- message persistence, database pagination, deterministic ordering, received-message read state, and omission of protected phone/location fields;
- block uniqueness, message blocking, Direct Invitation blocking, unblocking, and historical record retention;
- report context validation, admin-only moderation, safe notification payloads, and account-scoped AM/EN preferences;
- participant-only Booking dispute creation, atomic `UNDER_REVIEW` history, duplicate-active-dispute prevention, admin case review/resolution, internal-note privacy, and conservative post-resolution Booking state.

The complete suite also reran all prior authentication, Google identity, verification, protected storage, discovery, Posting Fee, proposals, transactional Booking/concurrency, lifecycle, privacy, and architecture-boundary tests.

## Additional gate checks

- `./gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain`: passed (`BUILD SUCCESSFUL`, 8 tasks executed).
- `docker compose config --quiet`: passed.
- OpenAPI live export: 77 paths / 90 operations; Milestone 7 routes present.
- Postman collection/environment: valid JSON; 18 folders / 24 variables.
- No live notification provider or external service was required.
- No tests were disabled or skipped.

