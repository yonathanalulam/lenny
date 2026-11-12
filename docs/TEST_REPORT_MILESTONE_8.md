# Milestone 8 test report

Executed locally on 2026-11-12 from `codex/milestones-7-8-release` after the reciprocal-feedback, administration, and Spring AI implementation.

## Environment

- Java toolchain: 21
- Spring Boot: 4.1.2
- Database: PostgreSQL 17 Testcontainers; H2 was not used
- Schema migration range: V1–V10
- AI tests: deterministic project-owned test adapter; no live provider call

## Clean PostgreSQL gate

Command:

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
```

Result: **PASS** in 2m 13s. JUnit XML contained 13 suites and 35 tests: 35 passed, 0 failures, 0 errors, 0 skipped. Aggregate test time was 109.453 seconds.

## Milestone 8 coverage

- Completed-only reciprocal feedback, participant roles, unrelated-user denial, 1–5 validation, database-backed duplicate prevention, privacy-safe public reviews, aggregates, rating discovery filter/sort, unrated semantics, and hide/restore moderation.
- Admin authorization, dashboard values against known fixtures, combined audit ordering/filtering/pagination, account-status credential invalidation, refresh-session revocation, and self-moderation denial.
- Active-category suggestion, invalid/inactive category rejection, malformed output, unavailable provider, confidence validation, and proof that suggestion does not persist a request or autonomous decision.
- Architecture checks enforce the Booking feedback query boundary and keep Spring AI types in the integration adapter rather than marketplace application/domain code.

## Packaging and contract gate

- `clean test bootJar --rerun-tasks --no-daemon --console=plain`: **PASS** in 3m 7s; all 8 tasks executed and the executable Boot JAR was produced.
- `docker compose config --quiet`: **PASS**.
- Live application startup against PostgreSQL 17.11: **PASS**; Flyway validated 10 migrations, applied V10 over the V9 local schema, and Hibernate validation completed.
- Regenerated OpenAPI: **86 paths / 99 operations**; feedback, dashboard, account administration, audit, and category-suggestion routes present.
- Postman collection/environment: **valid JSON**, 21 folders, 73 requests, and 25 environment variables.
- No live AI provider or secret was required for any gate.
