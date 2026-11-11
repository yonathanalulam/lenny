# Skilled Services Booking Platform — Backend

Deployable Kotlin/Spring Boot backend for an Addis Ababa home-services marketplace. One modular-monolith REST API and one PostgreSQL database serve web, mobile, and future admin clients.

This branch completes Milestones 1–5: native/Google platform sessions, profiles and verification, provider discovery, service requests and invitations, Posting Fee publication, proposals, and transactional Booking confirmation. Booking confirmation atomically accepts exactly one Proposal, reserves the provider interval, closes competing Proposals, and selects the provider on the request.

## Stack and architecture

- Kotlin, Java 21 toolchain, Spring Boot 4.1.2, MVC, Security and Data JPA
- PostgreSQL 17, Flyway V1–V7, Testcontainers
- JWT access tokens plus opaque server-side refresh sessions
- OpenAPI/Swagger, RFC 9457 ProblemDetail responses and correlation IDs
- Modular-monolith packages with domain/application/API/infrastructure boundaries
- Protected-object storage abstraction for verification evidence and request attachments

Flyway owns schema evolution; Hibernate validates it. Clients never connect directly to PostgreSQL. See [architecture](docs/architecture.md) and the [team handoff](docs/TEAM_HANDOFF.md).

## Prerequisites

- Docker Desktop (Linux containers)
- JDK 21, or a JDK from which Gradle can obtain the Java 21 toolchain
- PowerShell on Windows for the supplied helper scripts

## Quick start

```powershell
Copy-Item .env.example .env
docker compose up -d postgres

$bytes = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)

.\gradlew.bat bootRun
```

Open Swagger UI at `http://localhost:8080/swagger-ui.html`, OpenAPI JSON at `http://localhost:8080/v3/api-docs`, and health at `http://localhost:8080/actuator/health`. No permanent admin password is bundled; the explicitly enabled local bootstrap is documented in [local development](docs/LOCAL_DEVELOPMENT.md).

## Verify

Docker must be running because integration tests use real PostgreSQL rather than H2.

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain
.\scripts\smoke-test.ps1
```

## Developer references

- [Authentication and session model](docs/AUTHENTICATION.md)
- [Complete API reference](docs/API_REFERENCE.md)
- [Fresh-clone local development](docs/LOCAL_DEVELOPMENT.md)
- [Architecture and contributor handoff](docs/TEAM_HANDOFF.md)
- [Executed Milestones 1–4 test report](docs/TEST_REPORT_MILESTONES_1_TO_4.md)
- [Frontend integration contract](docs/frontend-integration.md)
- [Generated OpenAPI contract](docs/openapi.json)
- [Postman collection and local environment](docs/postman/SkilledServices.postman_collection.json)

The next planned feature milestone is **Milestone 6 — Booking Lifecycle, Completion, Cancellation and History**.
