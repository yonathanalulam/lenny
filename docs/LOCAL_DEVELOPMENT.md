# Local development

## Prerequisites

- JDK 21 (the Gradle toolchain targets Java 21)
- Docker Desktop with Linux containers
- PowerShell on Windows, or an equivalent shell
- No system Gradle installation is required; use the wrapper

## Fresh-clone setup

```powershell
git clone <repository-url> Lenny
Set-Location Lenny
Copy-Item .env.example .env
```

`.env` is ignored by Git. Docker Compose reads it automatically, but Gradle `bootRun` needs variables exported into the current shell. At minimum generate `JWT_SECRET_BASE64` as described in [AUTHENTICATION.md](AUTHENTICATION.md).

Google authentication is optional. To enable it, export a comma-separated allowlist without committing client IDs: `$env:GOOGLE_ALLOWED_CLIENT_IDS="web-client-id,android-client-id,ios-client-id"`. With no value, native auth works normally and Google exchange fails closed.

Safe local database defaults are already reflected in `docker-compose.yml`:

```powershell
docker compose up -d postgres
docker compose ps
```

Then export variables or load your `.env` using your preferred local tooling and run:

```powershell
.\gradlew.bat bootRun
```

Flyway automatically applies V1–V5 and Hibernate validates the resulting schema.

## Useful URLs

- API health: `http://localhost:8080/api/v1/health`
- Actuator health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Tests and build

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat clean test bootJar --rerun-tasks --no-daemon --console=plain
docker compose config --quiet
```

Docker must be running. Integration tests use PostgreSQL 17 Testcontainers, not H2.

Refresh the checked-in frontend contract while the backend is running:

```powershell
.\scripts\export-openapi.ps1
```

Run the lightweight live API check:

```powershell
.\scripts\smoke-test.ps1
```

## Protected local files

The default protected-object root is `./data/protected`. It contains verification evidence and request attachments and is ignored by Git. API responses expose authorized download paths, never filesystem paths or storage keys.

## Local administrator bootstrap

There is no public admin signup. An optional bootstrap is available only outside the `prod` profile and is disabled by default:

```powershell
$env:DEV_ADMIN_ENABLED = "true"
$env:DEV_ADMIN_EMAIL = "local-admin@example.test"
$env:DEV_ADMIN_PASSWORD = "use-a-unique-local-password"
.\gradlew.bat bootRun
```

The password must be 12–128 characters and is BCrypt-hashed. If the email already belongs to a non-admin account, startup fails. `application-prod.yml` forces this mechanism off.

## Payment configuration

No production gateway is bundled. `POSTING_FEE_PROVIDER=unavailable` is the safe default and payment initiation returns `PAYMENT_PROVIDER_UNAVAILABLE`. The signed `local-test` adapter is for automated/local tests only and cannot load in the `prod` profile.

## Safe reset

To reset only the project-owned local PostgreSQL volume:

```powershell
docker compose down
docker compose down -v
```

The second command deletes local database data. To reset protected local objects, remove only the project `data/protected` directory after confirming the path. Never use a recursive delete against a broad directory.
