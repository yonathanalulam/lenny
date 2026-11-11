# Team handoff

## Project

This is the backend for the Skilled Services Booking Platform for Addis Ababa Homes. Website and mobile clients consume one REST API backed by one authoritative PostgreSQL database; clients never connect directly to PostgreSQL.

## Architecture

The application is a Kotlin/Spring Boot modular monolith. Each business package separates transport (`api`), use cases (`application`), domain models/ports (`domain`), and adapters (`infrastructure`). Flyway is the only schema writer and Hibernate runs in validation mode.

## Module ownership

- `identity`: accounts, credentials, roles, account status, JWTs, refresh sessions.
- `profiles`: customer/provider profiles, availability, marketplace verification, evidence, eligibility interfaces.
- `marketplace`: service categories, provider-category associations, approved-provider discovery/comparison.
- `requests`: service requests, protected attachments, Direct Invitations, Posting Fee, provider job feed, proposals and activity events.
- `shared`: ProblemDetail/correlation infrastructure, pagination, time and protected-object storage.
- `booking`: transactional confirmation, authoritative provider-time reservation, participant queries, lifecycle transitions, cancellation, and append-only status history.
- `communication`, `administration`, `integration`: reserved placeholders; do not assume product behavior exists there.

## Rules contributors must preserve

- Controllers call application services, never JPA repositories.
- A module does not directly mutate another module's owned tables.
- Requests/search use `CustomerEligibilityService` and `ProviderEligibilityService`; they do not query verification persistence.
- Authentication/contact ownership, profile completion and marketplace verification are separate.
- Customer-owned, provider-facing and public DTOs are separate privacy boundaries.
- Exact location/contact data is not exposed to providers before Booking confirmation.
- Payment technology stays behind `PostingFeePaymentGateway`.
- Protected bytes stay behind `ProtectedObjectStorage`; PostgreSQL stores metadata only.
- Add schema through a new Flyway migration; never edit an applied migration.

Architecture rules have executable coverage in `ArchitectureBoundaryTest`.

## Completed milestones

1. Native authentication, JWT/refresh sessions, roles, PostgreSQL/Flyway and deployment foundation.
2. Profiles, availability, onboarding, verification/admin review and marketplace eligibility.
3. Categories, approved-provider discovery/comparison, requests, invitations, drafts and attachments.
4. Verified Posting Fee publication, provider job feed, invitation responses and proposal submission/review/rejection/withdrawal.
4.5. Google ID-token verification, explicit account linking, Google-only accounts, and platform JWT/session issuance.
5. Transactional Proposal acceptance and Booking confirmation with PostgreSQL provider-schedule locking, conflict-aware discovery, post-confirmation privacy, and idempotency.
6. Provider start/completion report, customer completion confirmation, customer pre-service cancellation, schedule release, filtered history, and lifecycle audit records.

## Not implemented

- Messaging and notifications
- Ratings/reviews
- Support cases
- AI category suggestion
- Final reporting/admin dashboards
- A real production payment gateway
- Provider-initiated cancellation policy and dispute intake/resolution workflow

The next product milestone should select one approved later boundary (communication/support, ratings, or AI category suggestion) rather than extending Booking policy implicitly.

## Adding a feature

Use this sequence:

1. Domain model and project-owned ports.
2. Application service and transaction boundary.
3. Infrastructure adapter/persistence.
4. API DTO/controller with privacy and role checks.
5. New Flyway migration when schema changes.
6. Unit, PostgreSQL integration, HTTP and concurrency tests proportional to risk.
7. Regenerated OpenAPI and updated frontend/docs.

## Team workflow

`main` is the stable integration branch. Begin Milestone 5 from the `backend-m4-stable` tag (or its matching `main` commit), create a feature branch such as `feature/booking-milestone-5`, and merge through review. Avoid parallel edits to the same aggregate/service. Suggested ownership areas are Identity/Auth, Profiles/Verification, Marketplace/Discovery, Requests/Payments/Proposals, future Booking, future Communication/Support, and future Ratings/Admin/AI. Merge cross-module interface changes deliberately and keep commits scoped.

Start with [README.md](../README.md), [LOCAL_DEVELOPMENT.md](LOCAL_DEVELOPMENT.md), [AUTHENTICATION.md](AUTHENTICATION.md), and [API_REFERENCE.md](API_REFERENCE.md).
