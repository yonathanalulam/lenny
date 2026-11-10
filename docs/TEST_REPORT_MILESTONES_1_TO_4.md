# Test report — Milestones 1–4 checkpoint

## Executed environment

- Test date: **2026-11-10** (Africa/Nairobi)
- Host runtime: Java `23.0.1`; Gradle toolchain/JVM target: **Java 21**
- Spring Boot: **4.1.2**; Kotlin: **2.3.21**
- Docker Desktop engine: **29.9.2**, running Linux containers
- Database: fresh `postgres:17-alpine` Testcontainers instances; packaged-JAR smoke used PostgreSQL **17.11** from Docker Compose
- Persistence: Flyway migrations V1–V5 followed by Hibernate schema validation; H2 was not used

## Definitive result

| Item | Result |
| --- | --- |
| Test suites/classes | 8 |
| Tests | 18 |
| Passed | 18 |
| Failed | 0 |
| Skipped | 0 |
| Errors | 0 |
| `clean test` | PASS |
| Uncached `clean test bootJar` | PASS; 8/8 Gradle tasks executed |
| Executable Boot JAR | PASS (`build/libs/skilled-services-platform-0.1.0-SNAPSHOT.jar`) |
| Docker Compose validation | PASS |
| Live packaged-JAR smoke | PASS on isolated port 18080 |
| Clean migration | PASS: empty schema → V1 → V2 → V3 → V4 → V5 |

Test cases by suite:

| Suite | Tests | Type |
| --- | ---: | --- |
| `AuthenticationPostgresIntegrationTest` | 6 | PostgreSQL + Spring HTTP/MockMvc |
| `AuthFlowPostgresIntegrationTest` | 1 | PostgreSQL service/integration |
| `AuthServiceTest` | 1 | Unit with in-memory ports |
| `ProfilesVerificationPostgresIntegrationTest` | 1 | PostgreSQL + HTTP end-to-end flow |
| `EligibilityServicesTest` | 6 parameterized cases | Unit |
| `MarketplaceRequestsPostgresIntegrationTest` | 1 | PostgreSQL + HTTP end-to-end flow |
| `PostingFeeProposalsPostgresIntegrationTest` | 1 | PostgreSQL + HTTP + concurrency flow |
| `ArchitectureBoundaryTest` | 1 | Static architecture boundary test |

Several integration methods intentionally contain a sequential business flow with many independent HTTP and persistence assertions. The new authentication concerns were split into six focused test methods during this checkpoint. The remaining marketplace flows retain shared setup to avoid multiplying expensive PostgreSQL/bootstrap work; future changes can split them when fixtures are reusable without weakening the concurrency scenario.

## Commands actually executed

```powershell
# Original checkpoint baseline (passed; some Gradle work came from cache)
.\gradlew.bat clean test --no-daemon --console=plain

# Baseline forced execution before stabilization changes (passed)
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain

# Focused auth verification (final rerun passed 6/6)
.\gradlew.bat test --tests "com.skilledservices.platform.identity.AuthenticationPostgresIntegrationTest" --rerun-tasks --no-daemon --console=plain

# Definitive final verification (passed 18/18, zero skipped)
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain
docker compose config --quiet

# Packaged JAR + Compose PostgreSQL, then:
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:18080
```

During development, the first focused auth run found one failure: malformed Jakarta-validation input returned Spring Boot's generic 400 ProblemDetail without the project's stable `code`. `ApiExceptionHandler` precedence and explicit invalid-body/method-validation handling were corrected. The final focused and complete suites passed. Port 8080 was already occupied during live verification, so the project JAR was deliberately started on 18080 rather than stopping an unrelated process.

## Milestone 1 — identity and access

Coverage is unit, PostgreSQL integration, and HTTP/API.

- Customer and provider registration succeed and persist accounts with the exact intended role.
- Unknown JSON properties cannot grant `ADMIN`; there is no public admin-registration endpoint.
- BCrypt hashes, rather than plaintext passwords, are persisted.
- Duplicate email/phone and malformed registration return stable ProblemDetail contracts.
- Customer/provider login, wrong password, unknown account, suspended/deactivated accounts are exercised.
- JWT issue/acceptance, missing/invalid/expired token handling, and database-backed account-state invalidation are exercised.
- `/me` uses the authenticated subject and omits password/refresh secrets.
- Refresh rotation is one-use; old/revoked tokens fail. Logout revokes the current refresh session.
- Representative CUSTOMER, SERVICE_PROVIDER, ADMIN and unauthenticated boundaries are asserted at HTTP level.
- The live smoke independently registers both roles, calls `/me`, rotates a refresh token, calls provider onboarding, and checks missing-token rejection.

Not covered: browser cookie-based auth (not implemented), OAuth/Google Sign-In (not implemented), distributed JWT-key rotation, or external penetration testing.

## Milestone 2 — profiles, availability and verification

Coverage is unit plus PostgreSQL HTTP/integration.

- Customer/provider profile ownership and onboarding projections.
- Provider availability replacement, validation, isolation between providers, and retrieval.
- Customer/provider verification submissions, invalid upload type/size behavior, pending-state rules.
- Admin queue/detail/approve/reject flow and resubmission behavior.
- Eligibility across `PENDING`, `APPROVED`, and `REJECTED` for both roles.
- Protected evidence: owner/admin access and unrelated-user denial.

Not covered: external malware scanning/object-store adapters, large-file performance, or real administrator operational provisioning in a deployed environment.

## Milestone 3 — discovery, requests and invitations

Coverage is PostgreSQL HTTP/integration plus architecture testing.

- Admin category creation/update/list, public active hierarchy, provider category assignment.
- Approved-provider discovery, filters/detail/comparison and exclusion of non-eligible providers.
- Public/provider DTO privacy: email, phone and exact location are absent where required.
- Eligible customer request creation, ownership, update/cancel state behavior, direct invitations, and private job drafts.
- Protected request attachment upload/download authorization and absence of storage keys from DTOs.
- Invitation owner access and withdrawal after request cancellation.
- Job publication request stops at `AWAITING_POSTING_FEE`.
- OpenAPI assertions ensure relevant endpoints exist.
- Architecture test prevents controller→repository access and verification-persistence coupling from marketplace/requests.

Not covered: high-volume discovery/load behavior, external protected-storage adapters, or exhaustive invalid-value fuzzing.

## Milestone 4 — Posting Fee, jobs and proposals

Coverage is PostgreSQL HTTP/integration and an explicit concurrency scenario.

- Payment initiation, configured ETB amount, unavailable/wrong-state behavior and transaction persistence.
- Signed callback verification, bad signature rejection, wrong amount/currency rejection, failed payment behavior and success.
- Callback replay/idempotency and atomic job publication to `OPEN`.
- Two simultaneous success callbacks; exactly one successful transaction/publication outcome remains.
- Provider OPEN job list/detail, category/eligibility filtering, attachment access and exact-location/contact privacy.
- Invitation accept/decline, repeated/withdrawn/ineligible/foreign invitation behavior.
- Proposal submit, uniqueness/eligibility rules, customer list/detail/ownership, rejection and provider withdrawal.
- Assertions confirm invitation acceptance does not create a proposal/Booking, rejected proposals do not close the request, and proposal-accept/OpenAPI endpoints do not exist.

Not covered: a real payment provider, network retry behavior outside the local adapter, production webhook infrastructure, final proposal acceptance, or Booking—all intentionally absent.

## Migration and contract verification

Every final PostgreSQL Spring context applied and validated V1–V5 on a clean Testcontainers database. Separately, the packaged JAR started against a new Compose volume: Flyway reported an empty schema, validated five migrations, applied V1 through V5 in order, reached version 5, and Hibernate initialized successfully without manual SQL.

The generated `build/generated-openapi/openapi.json` and checked-in `docs/openapi.json` had identical SHA-256 content after the final test. The contract contains **48 paths and 55 operations**, with no Booking, proposal-accept, or Google OAuth operation.

## Continuous integration

`.github/workflows/backend-ci.yml` runs on pushes to `main` and pull requests targeting `main`. It uses an Ubuntu runner, Temurin Java 21, the Gradle wrapper and Gradle caching, then runs the authoritative clean test command followed by `bootJar`. PostgreSQL integration tests continue to use Testcontainers and the runner's Docker service; CI does not substitute H2 or disable integration tests. This workflow was structurally reviewed and its commands were verified locally. A remote CI result is recorded only after GitHub executes the pushed workflow.
