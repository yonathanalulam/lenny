# API reference — Milestones 1–7

This is the human-readable companion to [`openapi.json`](openapi.json). The generated OpenAPI document and controller code remain the schema-level authority. All paths are relative to the API host (locally `http://localhost:8080`).

Protected calls use `Authorization: Bearer <access-token>`. `C` means `CUSTOMER`, `P` means `SERVICE_PROVIDER`, and `A` means `ADMIN`. Successful JSON field names and enums are defined in OpenAPI; the fields below identify the important inputs and outputs rather than duplicating every schema constraint.

## Common behavior

- Errors use `application/problem+json` with `status`, `title`, `detail`, `instance`, stable `code`, and `requestId` where available.
- Typical security errors are `security.authentication_required` (401) and `security.access_denied` (403).
- Pagination uses zero-based `page` and bounded `size`; page responses contain `content`, `page`, `size`, `totalElements`, and `totalPages`.
- IDs are UUIDs. Dates/times use ISO-8601 representations from OpenAPI.
- Authentication does not imply marketplace eligibility. Request, job, invitation, and proposal actions also enforce ownership, verification, profile, category, and state rules.

## System and identity

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/health` | Public | Process liveness; returns `status`, `service`, `timestamp`. | Does not prove dependency readiness. Actuator also exposes `/actuator/health`. |
| `POST /api/v1/auth/register/customers` | Public | Register native account from `email`, optional `phone`, `password`; returns access/refresh tokens and `user`. | Assigns only `CUSTOMER`; 400 validation, 409 `identity.contact_already_registered`. |
| `POST /api/v1/auth/register/service-providers` | Public | Same registration contract; creates provider identity and returns session. | Assigns only `SERVICE_PROVIDER`; 400 validation, 409 duplicate contact. |
| `POST /api/v1/auth/login` | Public | `email`, `password`; returns token pair and account. | Account must be active; 401 invalid credentials/account inactive. |
| `POST /api/v1/auth/google` | Public | Google `idToken`, optional first-registration `requestedRole` and `phone`; returns the platform token pair. | Validates Google issuer/signature/expiry/audience/email; existing-email collision requires explicit linking. |
| `POST /api/v1/auth/refresh` | Refresh token | `refreshToken`; atomically rotates session and returns a new token pair. | Token is one-use; 401 `identity.invalid_refresh_token` for expired/revoked/reused tokens. |
| `POST /api/v1/auth/logout` | Bearer, any role | Revokes the refresh session identified by the JWT; no body, 204 response. | A revoked refresh token cannot rotate again. |
| `GET /api/v1/me` | Bearer, any role | Returns authenticated account identity, contact verification, roles and marketplace-verification state. | Subject always comes from JWT; never returns password/token hashes. |
| `POST /api/v1/me/auth-identities/google` | Bearer, any role | Link a verified matching-email Google identity to the current account. | 400 email mismatch; 409 identity already linked. |

See [`AUTHENTICATION.md`](AUTHENTICATION.md) for examples and the full session model. Google is an identity provider only; the platform session remains authoritative.

## Communication, notifications, moderation, and preferences

| Method and path | Auth | Purpose and important behavior |
| --- | --- | --- |
| `POST /api/v1/me/conversations` | Any authenticated participant | Open/idempotently return a relationship-backed conversation using exactly one `requestId` or `bookingId`; Job Posting customers also provide `counterpartAccountId` before provider selection. |
| `GET /api/v1/me/conversations` | Participant | Deterministic paginated participant conversations. |
| `GET /api/v1/me/conversations/{id}` | Participant | Conversation metadata only; no phone/exact-location duplication. |
| `GET /api/v1/me/conversations/{id}/messages` | Participant | Database-paginated newest-first message history. |
| `POST /api/v1/me/conversations/{id}/messages` | Participant | Send a 1–4000 character message unless either participant has blocked the other. |
| `POST /api/v1/me/conversations/{id}/read` | Participant | Mark only received unread messages read. |
| `GET /api/v1/me/notifications` | Recipient | Page notifications; `unreadOnly=true` is supported. |
| `POST /api/v1/me/notifications/{id}/read` | Recipient | Idempotently mark one notification read. |
| `POST /api/v1/me/notifications/read-all` | Recipient | Mark all caller notifications read. |
| `GET`, `POST /api/v1/me/blocks` | Any authenticated marketplace participant | List own blocks or block an account with legitimate interaction history. |
| `DELETE /api/v1/me/blocks/{accountId}` | Block owner | Remove caller-owned block; history is retained. |
| `GET`, `POST /api/v1/me/reports` | Any authenticated marketplace participant | List own reports or submit a context-validated report. |
| `GET`, `POST /api/v1/me/support-cases` | Booking/request participant | List/create support cases; `BOOKING_DISPUTE` atomically moves eligible Booking to `UNDER_REVIEW`. |
| `GET /api/v1/me/support-cases/{id}` | Booking/request participant | Participant-safe case view; admin internal note is omitted. |
| `GET`, `PUT /api/v1/me/preferences` | Any authenticated account | Own `AM`/`EN` language and in-app notification category preferences. |
| `GET /api/v1/admin/reports` | A | Paginated/filterable report queue. |
| `POST /api/v1/admin/reports/{id}/review` | A | Move report to review/resolution/dismissal through the application service. |
| `GET /api/v1/admin/support-cases[/{id}]` | A | Paginated case queue/detail including safe internal notes. |
| `POST /api/v1/admin/support-cases/{id}/review` | A | Move case through `OPEN`, `IN_REVIEW`, `RESOLVED`, `CLOSED`. |

Blocking restricts new messages, Direct Invitations, and Proposals. It never cancels an existing Booking or erases messages/history. Persistent notifications contain concise event text and entity identifiers, never exact locations, phone numbers, report descriptions, message bodies, evidence, or provider callback payloads. Support resolution records an outcome only; it performs no refund or service-payment operation and does not guess a post-`UNDER_REVIEW` Booking state.

## Customer profile and onboarding

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/customers/me/profile` | C | Return the caller's private profile. | 404 when no profile exists. |
| `PUT /api/v1/customers/me/profile` | C | Create/replace profile using `serviceArea` and `preferredLanguage`. | Caller must own `CUSTOMER`; validation errors are 400. |
| `GET /api/v1/customers/me/onboarding` | C | Return profile, contact-verification, marketplace-verification and eligibility flags. | This is the canonical readiness projection. |

## Provider profile, onboarding and availability

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/providers/me/profile` | P | Return caller's private professional profile. | 404 when absent. |
| `PUT /api/v1/providers/me/profile` | P | Create/replace `professionalDescription`, `skills`, `experienceYears`, `serviceArea`, `pricingSummary`, and optional `portfolioSummary`. | Private contact/exact-location fields do not enter public projections. |
| `GET /api/v1/providers/me/onboarding` | P | Return setup, verification and marketplace-eligibility flags. | Canonical provider readiness projection. |
| `GET /api/v1/providers/me/availability` | P | Weekly schedule with `available` and day/time `ranges`. | Unset schedule is returned as unavailable. |
| `PUT /api/v1/providers/me/availability` | P | Replace the complete weekly availability document. | Validates non-overlap and time ranges; 400 on invalid schedule. |

## Verification and protected evidence

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/customers/me/verification` | C | Current customer verification submission/status/history projection. | Authentication and marketplace approval remain separate. |
| `POST /api/v1/customers/me/verification/submissions` | C | Multipart `evidenceType`, optional notes, and `files`; returns verification. | Only supported types/sizes; 409 if already pending; 413/415 upload errors. |
| `GET /api/v1/providers/me/verification` | P | Current provider verification state. | Same separation from authentication. |
| `POST /api/v1/providers/me/verification/submissions` | P | Multipart provider evidence submission. | Same pending/upload constraints. |
| `GET /api/v1/admin/verifications` | A | Filter by `subjectType`, `status`, `page`, `size`; returns summaries. | Normal users receive 403. |
| `GET /api/v1/admin/verifications/{verificationId}` | A | Submission, evidence metadata and decision history. | 404 `profiles.verification_not_found`. |
| `POST /api/v1/admin/verifications/{verificationId}/decision` | A | `decision` (`APPROVED`/`REJECTED`) and optional `reason`; returns updated verification. | Only a pending submission can transition; 409 invalid transition. |
| `GET /api/v1/verifications/evidence/{evidenceId}/content` | Owner or A | Stream protected evidence bytes with stored media type/name. | Only evidence owner or admin; 403 otherwise, 404 absent. |

## Service categories

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/categories` | Public | Active root categories with active children. | Inactive categories are hidden. |
| `GET /api/v1/categories/{categoryId}` | Public | One active category and children. | 404 for missing/inactive category. |
| `GET /api/v1/admin/categories` | A | All categories, including inactive. | 403 for non-admin. |
| `POST /api/v1/admin/categories` | A | `name`, optional localized name/description and `parentId`; returns category. | Supports roots and one child level; 409 duplicate. |
| `PATCH /api/v1/admin/categories/{categoryId}` | A | Partial names/description/`active` update. | 404 absent; 409 duplicate/invalid hierarchy. |
| `GET /api/v1/providers/me/categories` | P | Provider's selected category projections. | Caller-scoped. |
| `PUT /api/v1/providers/me/categories` | P | Replace selection using `categoryIds`; returns categories. | IDs must be valid active service categories. |

## Provider discovery and comparison

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/providers` | Public | Filter by `categoryId`, `serviceArea`, `available`, `minExperience`, requested date/time, `pricingText`, `sort`, `page`, `size`. | Only approved, eligible providers; no private contact/exact location. |
| `GET /api/v1/providers/{providerId}` | Public | Public provider projection, categories and availability summary. | 404 when provider is not marketplace-public. |
| `GET /api/v1/providers/compare?ids=...` | Public | Compare 2–5 approved providers without ranking. | 400 invalid count; 404 any non-public provider. |

## Customer requests and attachments

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `POST /api/v1/customers/me/requests` | C | Create `DIRECT_INVITATION` or private `JOB_POSTING`; request includes category, description, location, scheduling/budget and invited provider where applicable. | Customer must be eligible; direct target must be eligible; 403/409 business errors. |
| `GET /api/v1/customers/me/requests` | C | Page through caller-owned requests (`page`, `size`). | Never returns another customer's request. |
| `GET /api/v1/customers/me/requests/{requestId}` | C | Owner projection with private exact location and attachments. | 403 ownership, 404 absent. |
| `PATCH /api/v1/customers/me/requests/{requestId}` | C | Update mutable draft/request fields defined by `UpdateServiceRequestRequest`. | Only permitted states are editable; 409 invalid state. |
| `POST /api/v1/customers/me/requests/{requestId}/cancel` | C | Cancel an owner request; returns updated request. | State transition rules enforced; 409 invalid state. |
| `POST /api/v1/customers/me/requests/{requestId}/request-publication` | C | Move a job draft to `AWAITING_POSTING_FEE`. | Does not publish; only eligible job drafts; 409 otherwise. |
| `POST /api/v1/customers/me/requests/{requestId}/attachments` | C | Multipart `file`; returns protected attachment metadata/download URL. | Owner only; type/size restrictions produce 413/415. |
| `GET /api/v1/requests/attachments/{attachmentId}/content` | Owner, invited P, or A | Stream authorized protected attachment bytes. | 403 `requests.attachment_access_denied`; exact storage key/path is not exposed. |

## Direct invitations

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/providers/me/invitations` | P | Page caller's invitations with privacy-safe request details. | Invited provider only. |
| `GET /api/v1/providers/me/invitations/{invitationId}` | P | One caller-owned invitation. | 403 ownership; 404 absent. |
| `POST /api/v1/providers/me/invitations/{invitationId}/accept` | P | Mark interest accepted; returns invitation state. | Provider must remain eligible and invitation pending; no proposal/Booking is created. |
| `POST /api/v1/providers/me/invitations/{invitationId}/decline` | P | Decline a pending invitation. | 409 if no longer pending. |

## Posting Fee and payment webhook

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/customers/me/requests/{requestId}/posting-fee` | C | Amount, currency, latest transaction and publication status. | Owner only; request must be a job posting. |
| `POST /api/v1/customers/me/requests/{requestId}/posting-fee/payments` | C | Initiate through configured payment port; returns transaction/provider reference/next action. | Requires awaiting-fee state; 503 unavailable provider; duplicate-paid is 409. |
| `POST /api/v1/payment-webhooks/{provider}` | Provider-signed | Raw JSON plus provider signature headers; returns processing/idempotency result. | Adapter verifies signature, amount, currency and reference; 401 invalid callback. Not a user JWT endpoint. |

Successful verified callbacks atomically mark payment paid and publish the job `OPEN`. Callback replay is idempotent. The `local-test` adapter is non-production only; no production payment provider is bundled.

## Provider job feed

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `GET /api/v1/providers/me/jobs` | P | Page category-matched `OPEN` jobs with privacy-safe details. | Provider must be eligible; exact location/contact remain hidden. |
| `GET /api/v1/providers/me/jobs/{requestId}` | P | One accessible open job. | 403 ineligible; 404 closed/nonmatching/inaccessible job. |

## Proposals

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `POST /api/v1/providers/me/requests/{requestId}/proposals` | P | Submit `proposedPrice`, `expectedTimeline`, `providerMessage`, and optional `conditions`; returns proposal. | Accepted invitation or eligible open job; one proposal/provider/request; 409 violations. |
| `POST /api/v1/providers/me/proposals/{proposalId}/withdraw` | P | Withdraw caller's pending proposal. | 403 owner, 404 absent, 409 non-pending. |
| `GET /api/v1/customers/me/requests/{requestId}/proposals` | C | List proposals for caller-owned request. | Owner only; exposes proposal/provider public data, not credentials. |
| `GET /api/v1/customers/me/proposals/{proposalId}` | C | Detail for proposal on caller-owned request. | 403 owner; 404 absent. |
| `POST /api/v1/customers/me/proposals/{proposalId}/reject` | C | Reject a pending proposal; returns updated proposal. | 409 non-pending. |

## Booking confirmation and participant access

| Method and path | Auth | Purpose and principal fields | Important state/errors |
| --- | --- | --- | --- |
| `POST /api/v1/customers/me/proposals/{proposalId}/accept` | C | Supply offset-aware `scheduledStart` and `scheduledEnd`; atomically accepts the Proposal and returns the confirmed customer Booking DTO. | Rechecks ownership, account/marketplace eligibility, category relationship, weekly availability, and conflicts. Errors include `PROPOSAL_NOT_ACCEPTABLE`, `REQUEST_ALREADY_HAS_BOOKING`, `BOOKING_SCHEDULE_INVALID`, `PROVIDER_NOT_AVAILABLE`, and `BOOKING_SCHEDULE_CONFLICT`. |
| `GET /api/v1/customers/me/bookings` | C | Page through caller-owned Bookings, newest confirmation first; optional `status` filter. | `page` is zero-based; `size` is 1–100. Completed/cancelled history is retained. |
| `GET /api/v1/customers/me/bookings/{bookingId}` | C | Retrieve the caller's customer Booking projection. | 404 absent; 403 `BOOKING_NOT_OWNED` for another customer. |
| `GET /api/v1/providers/me/bookings` | P | Page through Bookings assigned to the caller; optional `status` filter. | Only the selected provider has rows. Later eligibility changes do not erase confirmed history. |
| `GET /api/v1/providers/me/bookings/{bookingId}` | P | Selected-provider projection including exact location and customer phone. | Losing/unrelated providers receive 403 `BOOKING_NOT_OWNED`. |

Confirmation is one database transaction: lock Proposal/request/provider schedule, recheck eligibility and weekly availability, detect half-open interval conflicts, insert the `CONFIRMED` Booking, accept the selected Proposal, reject competing pending Proposals, set the request to `PROVIDER_SELECTED`, and record activity. Any failure rolls back every step. The same successful Proposal is idempotent and returns its existing Booking; another Proposal cannot be accepted for that request. Currency is currently the platform's ETB proposal currency.

## Booking lifecycle

| Method and path | Auth | Transition | Important state/errors |
| --- | --- | --- | --- |
| `POST /api/v1/providers/me/bookings/{bookingId}/start` | P | `CONFIRMED -> IN_PROGRESS`; records `startedAt`. | Selected active provider only. Repeating at `IN_PROGRESS` is idempotent; other states return `BOOKING_INVALID_TRANSITION`. |
| `POST /api/v1/providers/me/bookings/{bookingId}/report-complete` | P | `IN_PROGRESS -> COMPLETION_PENDING`; records `providerReportedCompleteAt`. | Selected active provider only; repeating at target is idempotent. |
| `POST /api/v1/customers/me/bookings/{bookingId}/confirm-completion` | C | `COMPLETION_PENDING -> COMPLETED`; records `completedAt`. | Booking customer only; repeating at `COMPLETED` is idempotent. |
| `POST /api/v1/customers/me/bookings/{bookingId}/cancel` | C | `CONFIRMED -> CANCELLED`; optional `reason` up to 500 characters. | Pre-service customer cancellation only. Records actor/timestamp and releases the interval. |

Booking detail DTOs include lifecycle timestamps and privacy-safe `statusHistory` entries (`fromStatus`, `toStatus`, actor context, timestamp, optional reason). History is append-only. `CANCELLED` is the sole non-blocking state; completed/cancelled records remain queryable. Provider cancellation and dispute intake are not exposed because approved policy/category requirements are still ambiguous.

## Explicitly absent

There is no messaging, notification, rating/review, full support/dispute, escrow, or service-payment API at the Milestone 6 checkpoint.
