# Architecture

The platform is one Spring Boot modular monolith. Website, mobile, and administration clients all call the same `/api/v1` REST API. Only the backend connects to the single PostgreSQL database.

## Module boundaries

| Business boundary | Package | Current state |
| --- | --- | --- |
| Identity & Access | `identity` | Implemented vertical slice |
| Profiles, Verification & Trust | `profiles` | Profiles, availability, verification workflow, evidence, and eligibility implemented |
| Marketplace Discovery | `marketplace` | Categories, provider-category assignments, approved-provider discovery, and comparison implemented |
| Requests, Invitations, Proposals & Posting Fee | `requests` | Verified Posting Fee publication, provider job feed, invitation responses, and proposal review lifecycle implemented |
| Booking & Service Lifecycle | `booking` | Transactional confirmation, provider schedule reservation, lifecycle, cancellation, and history implemented |
| Communication & Support | `communication` | Relationship-scoped messaging, notifications, blocking/reporting, support/disputes, preferences, and moderation implemented |
| Reciprocal Feedback | `feedback` | Completed-Booking feedback, public provider aggregates/reviews, and moderation implemented |
| Administration, Audit & Reporting | `administration` | Read-only dashboard/audit projections implemented; identity owns account-status mutation |
| Integration Adapters | `integration` | Provider-neutral Spring AI category-suggestion adapter implemented |

Within a business module, `api` owns transport DTOs, `application` owns use-case orchestration, `domain` owns state and ports, and `infrastructure` implements persistence or provider-facing contracts. A module must call another module through an explicit application/domain interface; it must not write that module's tables.

## Identity data ownership

- `accounts` owns credentials, account lifecycle status, and contact-ownership flags.
- `account_roles` permits controlled multi-role accounts. There is no public administrator-registration path.
- `refresh_sessions` stores only a SHA-256 token fingerprint and supports rotation and revocation. Access tokens carry a session ID and credential version; protected requests re-check current account state.

Contact-verification delivery is intentionally deferred. `ContactVerificationDeliveryPort` is the integration contract; no fake email or SMS provider is installed.

Flyway is the only schema writer. Hibernate runs with `ddl-auto=validate` and Open Session in View is disabled.

## Profiles, verification, and trust ownership

- `customer_profiles` and `provider_profiles` contain profile-only data. Exact service addresses and account phone numbers are deliberately absent.
- `provider_availability` and its weekly ranges define base availability. Booking confirmation locks the provider availability row before conflict detection and insertion.
- `marketplace_verifications` is current state. `verification_submissions`, `verification_evidence`, and `verification_decisions` retain submission and administrative history.
- `protected_objects` stores internal object references only. Bytes live behind `ProtectedObjectStorage`; the local adapter writes beneath a configured private root. API responses never include storage keys or filesystem paths.
- Future Requests, Search, and Booking code must call `CustomerEligibilityService` or `ProviderEligibilityService`, not verification repositories.

Profile completion, evidence submission, contact ownership, and marketplace approval are separate state. Marketplace eligibility is granted only by an `APPROVED` marketplace verification.

## Marketplace discovery ownership

- `service_categories` supports root categories plus one subcategory level. The uniqueness rule is case-insensitive English name within the same parent; Amharic and English labels share one category ID.
- `provider_service_categories` retains historical references when a category is deactivated. Inactive categories and their assignments are excluded from new discovery.
- Marketplace calls `ProviderEligibilityService` for every public candidate and also requires an active Identity account. It reads safe profile and weekly-availability data through `ProviderDiscoveryProfileQuery`; it never reads verification persistence.
- Public provider DTOs omit credentials, contact data, exact addresses, verification evidence, administrative notes, and storage references. Discovery subtracts confirmed Booking conflicts through the Booking-owned `ProviderBookingAvailabilityQuery`; confirmation remains authoritative.

## Requests and invitations ownership

- `service_requests` explicitly distinguishes `DIRECT_INVITATION` and `JOB_POSTING`. Direct Invitations become `OPEN` atomically with their `PENDING` invitation. Job Postings follow `DRAFT -> AWAITING_POSTING_FEE -> OPEN`; only verified Posting Fee success may perform the final transition.
- The Requests module calls `CustomerEligibilityService` before request creation and `ProviderEligibilityService` before a Direct Invitation. It does not query verification tables.
- Direct Invitation creation atomically persists its `OPEN` request and `PENDING` invitation. Job Postings remain private until verified payment publishes them.
- Customer-owned DTOs contain exact service location. Provider invitation DTOs are separate types and structurally omit exact location and private contact data.
- Verification evidence and request attachments share the `shared.storage` protected-object boundary and local adapter. PostgreSQL stores metadata only; authorization gates every content download.

## Posting Fee and publication boundary

- `PostingFeePaymentGateway` is the only application-facing payment-provider port. Provider authentication and callback normalization happen in an infrastructure adapter before a database transaction begins.
- The default gateway is explicitly unavailable. The signed deterministic `local-test` adapter is conditional on configuration and excluded from the `prod` profile; configuring it in production prevents startup rather than simulating success.
- Payment initiation snapshots configured amount and currency into `posting_fee_transactions`. A partial unique PostgreSQL index permits only one `SUCCESSFUL` payment per request; provider event/reference indexes enforce replay idempotency.
- A verified success locks the payment and request and atomically changes payment to `SUCCESSFUL` and Job Posting to `OPEN`. Failed, invalid, mismatched, or incomplete events cannot publish.
- `marketplace_activity_events` stores non-sensitive lifecycle facts without callback payloads or payment credentials.

## Jobs, invitations, and proposals

- Provider job access reuses `ProviderEligibilityService`, active Identity state, active category assignments, and the existing general service-area model. It never reads verification persistence.
- Provider job and invitation DTOs structurally omit exact location and customer contact data. Explicit request attachments are authorized separately.
- Invitations support `PENDING`, `ACCEPTED`, `DECLINED`, `WITHDRAWN`, and `EXPIRED`. Acceptance means interest only and creates neither Proposal nor Booking.
- One proposal record is allowed per provider/request. Milestone 4 permits `PENDING -> REJECTED` or `PENDING -> WITHDRAWN`; `EXPIRED` is representable but automatic timing is deferred.
- `ACCEPTED` is reachable only through Booking confirmation. The acceptance transaction locks Proposal, request, and provider schedule state; rechecks both participants and availability; inserts one Booking; accepts the selected Proposal; rejects competing pending Proposals; and sets the request to `PROVIDER_SELECTED`. Any failure rolls back the complete operation.

## Booking confirmation and schedule ownership

- `bookings` is the authoritative provider-time reservation; no duplicate reservation table exists.
- Intervals are persisted as `timestamptz`/`Instant` and use half-open overlap semantics: `existingStart < requestedEnd && existingEnd > requestedStart`. Adjacent appointments are allowed.
- Weekly availability is interpreted in `Africa/Addis_Ababa`. The availability row is pessimistically locked so confirmations for the same provider serialize across application instances.
- Database uniqueness permits one Booking per request and Proposal and one accepted Proposal per request. Repeating the same successful acceptance returns the existing Booking.
- Customer and selected-provider DTOs are separate. Only the selected provider can retrieve exact location and customer phone after confirmation.

## Booking lifecycle and history

- The aggregate owns `CONFIRMED -> IN_PROGRESS -> COMPLETION_PENDING -> COMPLETED`; controllers cannot assign state directly. The Communication application calls the Booking-owned `BookingDisputePort` to move an eligible participant Booking to `UNDER_REVIEW` atomically with append-only history.
- The provider starts and reports completion. The customer confirms completion. Repeating an operation already at its target state is idempotent and does not append duplicate history.
- Customer cancellation is allowed only from `CONFIRMED`. It retains the Booking, actor, timestamp, safe reason, and history. Provider-initiated cancellation is deferred pending approved policy.
- `booking_status_history` is append-only through its store and is the authoritative transition audit. Broad marketplace activity is not duplicated for every lifecycle transition.
- All statuses except `CANCELLED` remain blocking for interval conflict purposes. Cancellation therefore releases provider time without deleting service history.
- Marketplace eligibility gates new participation, not access to an already confirmed Booking. Active-account security still gates lifecycle actions.

## Communication, support, and moderation

- A Conversation exists only for a proven request/Proposal/invitation or Booking relationship. `MarketplaceRelationshipQuery` is a read-only projection; Communication never mutates Requests or Booking tables.
- Conversation and Message DTOs contain identifiers and user-authored body text only. They do not duplicate phone numbers, exact locations, verification evidence, credentials, admin notes, or storage references.
- Message history, conversations, notifications, reports, and support cases use deterministic database pagination. Blocking prevents new messages, Direct Invitations, and Proposals but never deletes historical messages, Bookings, or support evidence.
- In-app notifications are persistent and polling-compatible. `PlatformNotificationPort` decouples existing marketplace modules from the Communication persistence adapter. No SMS, email, push, WebSocket, or SSE provider is implied.
- A Booking dispute creates a real `support_cases` record and transitions an eligible Booking to `UNDER_REVIEW` in one transaction. Resolution records the support outcome but deliberately leaves the historical Booking in `UNDER_REVIEW`; no financial/refund behavior exists.
- `platform_audit_events` contains only safe identifiers and event types. Message bodies, report/support descriptions, evidence, and protected data are never copied into audit events.
- Account preferences persist only `AM`/`EN` and notification-category switches. Arbitrary user content is not translated.

## Feedback, administration, and AI

- `feedback` accepts exactly one rating in each permitted direction for a `COMPLETED` Booking. The Booking module exposes a read-only participant/state query; Feedback never mutates Booking persistence.
- Public provider averages and counts include only visible customer-to-provider feedback. Unrated providers use `averageRating=null` and `ratingCount=0`; provider-to-customer feedback is never mixed into discovery scores.
- Moderation hides/restores public text and aggregate participation while retaining the original record, score, actor, reason, and timestamps for audit.
- `administration` is read-only reporting infrastructure over PostgreSQL projections. Cross-module state changes use owning application services, such as Identity's account administration service, which revokes refresh sessions and increments credential version.
- Category suggestion application code owns `CategorySuggestionPort`. The `integration.ai` adapter uses Spring AI's provider-neutral `ChatModel`; it receives only the description and active category candidates, returns structured data, and is disabled by default.
- AI output is advisory and must match an active category ID. Invalid, malformed, inactive, or unavailable output fails safely without creating a request or persisting a choice.

## Query and pagination hardening

- Booking participant lists apply status filtering, deterministic ordering, count, limit, and offset in PostgreSQL before enriching only the returned page.
- Provider discovery batches account status, marketplace approval, availability, category assignments, category definitions, and rating aggregates; query count no longer grows per candidate provider.
- The provider Job Posting feed computes the caller's eligibility context once and batch-loads attachments for the returned page rather than querying per job.
- Message and notification history, feedback lists, support/report queues, and audit events remain database-paginated. Rating aggregates use one grouped query over the requested provider IDs.
- V11 adds focused participant-list and open-job ordering indexes; no speculative cache, search engine, or additional datastore was introduced.
