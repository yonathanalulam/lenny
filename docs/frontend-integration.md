# Frontend integration — onboarding, discovery, and requests

All endpoints use `/api/v1`, JSON camelCase fields, bearer access tokens, `application/problem+json` errors, and `X-Correlation-ID`. Swagger UI is available locally at `/swagger-ui.html`; the OpenAPI JSON is `/v3/api-docs`.

The checked-in snapshot is `docs/openapi.json`. With a local backend running, refresh it using `./scripts/export-openapi.ps1`.

## Onboarding state

Use these endpoints instead of inferring state from missing profile fields:

- `GET /customers/me/onboarding`
- `GET /providers/me/onboarding`

Example:

```json
{
  "accountStatus": "ACTIVE",
  "role": "SERVICE_PROVIDER",
  "setupComplete": true,
  "verificationStatus": "PENDING",
  "verificationSubmittedAt": "2026-11-06T09:00:00Z",
  "marketplaceEligible": false
}
```

`setupComplete` never implies verification approval. A provider remains private/ineligible while `PENDING` or `REJECTED`.

## Profiles and availability

| Method | Path | Role |
| --- | --- | --- |
| `GET`, `PUT` | `/customers/me/profile` | `CUSTOMER` |
| `GET`, `PUT` | `/providers/me/profile` | `SERVICE_PROVIDER` |
| `GET`, `PUT` | `/providers/me/availability` | `SERVICE_PROVIDER` |

Customer profiles contain only a general service area and preferred language. Exact job addresses must be collected by the future Service Request workflow. Provider profile fields are private until future discovery code checks provider eligibility.

Availability is a weekly template. `available` is a global switch; `ranges` contain `dayOfWeek`, `startTime`, and `endTime`. Times use local wall-clock values intended for Addis Ababa. Booking conflicts are intentionally not represented yet.

## Verification submission

Current state:

- `GET /customers/me/verification`
- `GET /providers/me/verification`

Submit evidence using `multipart/form-data`:

- `POST /customers/me/verification/submissions`
- `POST /providers/me/verification/submissions`

Parts:

1. `metadata`: `application/json`
2. one or more `files` parts

The descriptor and file lists are matched by position:

```json
{
  "evidence": [
    { "identifier": "identity-front", "type": "Identity evidence" },
    { "identifier": "qualification-1", "type": "Professional qualification" }
  ]
}
```

Allowed content types and size limits are deployment configuration. Defaults are PDF, JPEG, and PNG, with 10 MiB per file. The API returns evidence IDs and a protected download path, never internal storage keys.

An initial registration has `status=PENDING` and `submittedAt=null`. Submission sets `submittedAt`. A second submission while pending returns `VERIFICATION_ALREADY_PENDING`. A rejected user may resubmit. Clients cannot set approval state or reviewer identity.

## Administration

| Method | Path |
| --- | --- |
| `GET` | `/admin/verifications?subjectType=CUSTOMER&status=PENDING&page=0&size=20` |
| `GET` | `/admin/verifications/{verificationId}` |
| `POST` | `/admin/verifications/{verificationId}/decision` |

Only administrators may call these endpoints. Decision request:

```json
{
  "decision": "APPROVED",
  "reason": "Submitted verification information reviewed."
}
```

Only submitted `PENDING` verifications may transition to `APPROVED` or `REJECTED`. Decision history is append-only. A rejected user must submit again before another decision.

## Protected evidence

`GET /verifications/evidence/{evidenceId}/content` is allowed only to the evidence owner or an administrator. A cross-user attempt returns `403` with `FORBIDDEN_VERIFICATION_ACCESS`.

## Important error codes

- `PROFILE_INVALID`
- `INVALID_AVAILABILITY_RANGE`
- `VERIFICATION_ALREADY_PENDING`
- `VERIFICATION_NOT_FOUND`
- `INVALID_VERIFICATION_TRANSITION`
- `FORBIDDEN_VERIFICATION_ACCESS`
- `UNSUPPORTED_UPLOAD_TYPE`
- `UPLOAD_TOO_LARGE`

Verification means platform review of submitted information only. The UI must not describe it as a government background check, criminal-record check, competence guarantee, or safety guarantee.

## Service categories

Public, active-only browsing requires no access token:

- `GET /categories`
- `GET /categories/{categoryId}`

Each category contains one stable ID, `name.en`, `name.am`, an optional description, and `subcategories`. The hierarchy is intentionally limited to root category plus one subcategory level.

Administrator operations require `ADMIN`:

- `POST /admin/categories`
- `PATCH /admin/categories/{categoryId}`
- `GET /admin/categories`

English names are unique case-insensitively within the same parent. Deactivation is preferred to deletion. Providers configure one or more active categories with `GET|PUT /providers/me/categories`.

## Approved-provider discovery

- `GET /providers`
- `GET /providers/{providerId}`
- `GET /providers/compare?ids=<uuid>&ids=<uuid>`

Discovery query parameters:

- `categoryId` — a root matches itself and active children.
- `serviceArea` — case-insensitive text match.
- `available` — weekly availability switch.
- `minExperience` — 0–80.
- `requestedDate`, with optional paired `startTime` and `endTime` — weekly schedule hint.
- `pricingText` — case-insensitive match against the permitted pricing summary.
- `minRating` — 1–5; filters on visible customer-to-provider feedback aggregates.
- `sort` — `updatedAt,asc|desc`, `experience,asc|desc`, or `rating,asc|desc`.
- `page`, `size` — stable `PageResponse`, maximum size 100.

Only approved providers with active accounts and at least one active category appear. Pending, rejected, deactivated, and inactive-category-only providers return no public profile. Public responses never contain email, phone, exact address, evidence, moderation notes, or protected-object keys. Unrated providers return `averageRating=null` and `ratingCount=0`—do not render a fabricated zero-star score.

Availability is a discovery hint, not a reservation. Booking must recheck availability later.

## Service Requests

Customer endpoints require `CUSTOMER` and derive customer identity from the bearer token:

- `POST /customers/me/requests`
- `GET /customers/me/requests`
- `GET|PATCH /customers/me/requests/{requestId}`
- `POST /customers/me/requests/{requestId}/request-publication`
- `POST /customers/me/requests/{requestId}/cancel`
- `POST /customers/me/requests/{requestId}/attachments` as multipart part `file`

`type` is explicit: `DIRECT_INVITATION` or `JOB_POSTING`. Money is decimal, never floating point. A time window must supply both times with start before end. The backend enforces customer eligibility even if the frontend displays the action.

### Direct Invitation

`providerId` is required. The provider is rechecked for marketplace eligibility and category assignment. Creation atomically creates a `DRAFT` request plus a `PENDING` invitation; there is no Posting Fee.

Providers retrieve only invitations addressed to them:

- `GET /providers/me/invitations`
- `GET /providers/me/invitations/{invitationId}`

The provider projection includes general area and request details but structurally omits `exactLocation`, customer ID, phone, and email. Explicit request attachments are accessible to the invited provider through `GET /requests/attachments/{attachmentId}/content`.

### Job Posting draft

Job Postings must omit `providerId`. Creation starts at `DRAFT`. `request-publication` changes it only to `AWAITING_POSTING_FEE`; `postingFeeRequired=true` and `published=false` make the frontend state explicit. Editing material data while awaiting payment returns the request to `DRAFT`.

Unpaid drafts remain private. The provider job feed described below exposes a Job Posting only after verified Posting Fee success changes it to `OPEN`.

### Privacy and attachments

Customer request responses contain `exactLocation`; provider invitation responses use a different DTO and cannot serialize it. Attachment responses expose IDs and authorized download paths, never storage keys. Defaults remain PDF/JPEG/PNG and 10 MiB, controlled by deployment configuration.

New error codes include `CATEGORY_NOT_FOUND`, `CATEGORY_INACTIVE`, `CATEGORY_DUPLICATE`, `PROVIDER_NOT_PUBLICLY_AVAILABLE`, `PROVIDER_NOT_MARKETPLACE_ELIGIBLE`, `CUSTOMER_NOT_MARKETPLACE_ELIGIBLE`, `REQUEST_NOT_FOUND`, `REQUEST_NOT_OWNED`, `INVALID_REQUEST_TYPE`, `INVALID_REQUEST_STATE`, `INVALID_BUDGET_RANGE`, `INVALID_SERVICE_DATE`, `INVITATION_NOT_FOUND`, `FORBIDDEN_REQUEST_ATTACHMENT_ACCESS`, and `UNSUPPORTED_PROVIDER_SORT`.

## Posting Fee and Job Publication

Job Posting publication now follows:

1. Create `JOB_POSTING` → `DRAFT`.
2. Request publication → `AWAITING_POSTING_FEE`.
3. `GET /customers/me/requests/{requestId}/posting-fee` returns the configured amount/currency and current transaction state.
4. `POST /customers/me/requests/{requestId}/posting-fee/payments` creates or resumes a `PENDING` transaction and returns provider-neutral `nextAction` data.
5. Only an authenticated, verified provider webhook may atomically produce payment `SUCCESSFUL` and request `OPEN`.

A browser redirect is never payment proof. Poll the status endpoint after completing `nextAction`. Historical transactions retain their own amount and currency if configuration later changes.

The default payment provider is `unavailable`; initiation returns `PAYMENT_PROVIDER_UNAVAILABLE`. The deterministic signed `local-test` adapter is for automated/local testing only, is never enabled silently, and cannot be created under the `prod` profile.

Webhook endpoints are provider infrastructure, not frontend APIs. Frontends must not call them or synthesize payment success.

## Provider Job Posting feed

- `GET /providers/me/jobs?page=0&size=20`
- `GET /providers/me/jobs/{requestId}`

Only active, marketplace-approved providers may use the feed. Results contain only `OPEN` Job Postings matching active category assignments and the currently modeled general service area. `DRAFT`, `AWAITING_POSTING_FEE`, and `CANCELLED` requests are invisible.

Provider job DTOs include service description, general area, schedule preference, budget, constraints, and explicitly attached request files. They never contain exact location, customer email/phone, verification data, or storage keys.

## Direct Invitation responses

A successfully sent Direct Invitation now has request `status=OPEN` and invitation `status=PENDING`; it never requires a Posting Fee.

- `POST /providers/me/invitations/{invitationId}/accept`
- `POST /providers/me/invitations/{invitationId}/decline`

Invitation states are `PENDING`, `ACCEPTED`, `DECLINED`, `WITHDRAWN`, and `EXPIRED`. Customer cancellation reuses the existing request cancellation operation and changes a pending invitation to `WITHDRAWN`.

`ACCEPTED` means the provider is interested. It does not reserve time, create a Proposal, agree a final price, or create a Booking. The provider submits a separate Proposal.

Automatic expiration timing is deferred because no approved duration exists.

## Proposals

Provider operations:

- `POST /providers/me/requests/{requestId}/proposals`
- `POST /providers/me/proposals/{proposalId}/withdraw`

Customer review:

- `GET /customers/me/requests/{requestId}/proposals`
- `GET /customers/me/proposals/{proposalId}`
- `POST /customers/me/proposals/{proposalId}/reject`

For Direct Invitations, the invitation must first be `ACCEPTED`. For Job Postings, the request must be `OPEN` and the provider must satisfy active-account, approval, category, and general-area eligibility.

One Proposal is permitted per provider/request. A `PENDING` Proposal may become `REJECTED` or `WITHDRAWN`; `EXPIRED` is reserved for a future configured policy. Resubmission after withdrawal or rejection is not implemented pending product confirmation.

## Proposal acceptance and Booking confirmation

The customer confirms a provider by calling `POST /customers/me/proposals/{proposalId}/accept` with ISO-8601 offset-aware `scheduledStart` and `scheduledEnd`. Do not send customer, provider, request, Proposal status, or Booking status IDs; the backend derives them.

The response is a `CONFIRMED` Booking. The operation is atomic and rechecks both accounts, marketplace eligibility, category relationship, current weekly availability, and existing Booking conflicts. Intervals are half-open, so `10:00–11:00` may be followed by `11:00–12:00`. A repeated successful request for the same Proposal returns the same Booking; disable double taps for UX, but do not depend on the UI for correctness.

Use `GET /customers/me/bookings` and `GET /customers/me/bookings/{bookingId}` for customer views. Use the separate provider endpoints under `/providers/me/bookings`; only the selected provider receives exact location and customer phone after confirmation. Losing providers never receive those fields. Discovery requests with a date/time automatically exclude overlapping confirmed Bookings, but the accept operation remains authoritative.

## Booking lifecycle

The selected provider calls `/providers/me/bookings/{bookingId}/start`, then `/report-complete`. The booking customer calls `/customers/me/bookings/{bookingId}/confirm-completion`. Successful responses return the updated participant-specific Booking DTO and `statusHistory`.

Customer pre-service cancellation uses `POST /customers/me/bookings/{bookingId}/cancel` with `{ "reason": "optional safe text" }`. It is valid only while `CONFIRMED`; after service starts, show a support/dispute path when that later module exists. A cancelled interval becomes discoverable/bookable again. Never remove completed or cancelled rows from local history views.

List endpoints accept an optional `status` enum filter and retain deterministic newest-confirmed-first pagination. Lifecycle retries already at their target state are safe and do not create duplicate history. Marketplace eligibility changes do not hide existing Bookings, although suspended/deactivated accounts cannot perform lifecycle actions.

Provider cancellation and dispute opening are intentionally absent pending approved policy. Do not synthesize either transition client-side.

Additional stable errors include `POSTING_FEE_NOT_REQUIRED`, `POSTING_FEE_ALREADY_PAID`, `PAYMENT_PROVIDER_UNAVAILABLE`, `PAYMENT_INITIATION_FAILED`, `PAYMENT_VERIFICATION_FAILED`, `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_CALLBACK_INVALID`, `JOB_NOT_OPEN`, `JOB_NOT_ELIGIBLE`, `INVITATION_NOT_PENDING`, `INVITATION_NOT_OWNED`, `PROPOSAL_ALREADY_EXISTS`, `PROPOSAL_NOT_FOUND`, `PROPOSAL_NOT_PENDING`, and `PROPOSAL_NOT_OWNED`.

## Communication and notifications

Open a conversation with `POST /me/conversations` using either `bookingId`, or `requestId` plus `counterpartAccountId` when a Job Posting still has multiple provider relationships. Then use `/me/conversations/{id}/messages` and `/read`. Conversation/message payloads intentionally omit exact location and phone data; after confirmation retrieve permitted contact/location only from the selected-provider Booking endpoint.

Poll `GET /me/notifications?unreadOnly=true&page=0&size=20`. Mark one with `POST /me/notifications/{id}/read` or all with `/read-all`. Notification text is a safe UI hint; use `relatedEntityType`/`relatedEntityId` to navigate and then fetch the authoritative resource.

Blocking is caller-scoped under `/me/blocks`. It disables new messages, invitations, and proposals between the pair, but the UI must continue showing historical messages and Booking obligations. Never imply that blocking automatically cancels a Booking.

## Reports, support, and preferences

Submit a report through `POST /me/reports` with a bounded category and, when available, `USER`, `REQUEST`, `BOOKING`, or `CONVERSATION` context. Users can see their own report status; only administrators can moderate.

Create support via `POST /me/support-cases`. A `BOOKING_DISPUTE` requires `bookingId` and can move an eligible Booking to `UNDER_REVIEW`. The frontend must not promise refunds, escrow, payment reversal, or a particular post-resolution Booking transition. Participant case views never include `internalNote`.

Use `GET|PUT /me/preferences` for `preferredLanguage` (`AM` or `EN`) and in-app notification switches. Stable codes/enums are localizable by web/mobile; message bodies, reviews, and other user content are not translated by the backend.

## Feedback and ratings

After a Booking reaches `COMPLETED`, show exactly one submission action to each participant:

- Customer: `POST /customers/me/bookings/{bookingId}/provider-feedback`
- Provider: `POST /providers/me/bookings/{bookingId}/customer-feedback`

Send `rating` from 1–5 and an optional `comment`. Treat duplicate and non-completed responses as business conflicts; disabling a double tap is useful UX but the backend uniqueness constraint is authoritative. Public reviews come from `GET /providers/{providerId}/reviews` and use a privacy-safe reviewer label. Only visible customer-to-provider feedback affects provider discovery.

## Administrator dashboard and audit

Use `GET /admin/dashboard` for current aggregates and `GET /admin/audit-events?page=0&size=20` for newest-first audit activity; optional `actorId` and `eventType` filters are supported. Account status changes use `POST /admin/accounts/{accountId}/status`. A successful status change invalidates active credentials, so clients must handle subsequent 401 responses normally.

## Advisory AI category suggestion

Call `POST /customers/me/category-suggestions` after the customer enters at least 10 characters of service description. Present the returned active category as a suggestion only. The customer must be able to keep it, choose another category, or continue manually when the API returns `AI_SUGGESTION_INVALID` or `AI_SUGGESTION_UNAVAILABLE`. Do not create the request automatically: the later customer-selected `categoryId` is authoritative.
# Google authentication

Web/mobile obtains a Google **ID token** from the Google client SDK and sends it to `POST /api/v1/auth/google`. The response is the same platform access/refresh pair used by native login. Never use the Google token as `Authorization: Bearer` for platform endpoints. First-time users supply `requestedRole` (`CUSTOMER` or `SERVICE_PROVIDER`); marketplace verification remains `PENDING`. Existing native-email collisions must log in natively and call `POST /api/v1/me/auth-identities/google` to link explicitly.
