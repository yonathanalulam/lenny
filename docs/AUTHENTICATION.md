# Authentication

The backend supports native email/password authentication and Google ID-token exchange. Both paths issue the same platform JWT access token and opaque, server-side refresh session. Google tokens are never accepted as API bearer tokens.

## Endpoints

| Method | Path | Authentication | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register/customers` | Public | Create a `CUSTOMER` account and session |
| `POST` | `/api/v1/auth/register/service-providers` | Public | Create a `SERVICE_PROVIDER` account and session |
| `POST` | `/api/v1/auth/login` | Public | Create a session using email and password |
| `POST` | `/api/v1/auth/google` | Public | Verify a Google ID token, resolve/register an account, and create a platform session |
| `POST` | `/api/v1/auth/refresh` | Public, refresh token in body | Rotate a refresh session and issue new tokens |
| `POST` | `/api/v1/auth/logout` | Bearer JWT | Revoke the access token's current refresh session |
| `GET` | `/api/v1/me` | Bearer JWT | Return the authenticated account |
| `POST` | `/api/v1/me/auth-identities/google` | Bearer JWT | Explicitly link Google to the authenticated account |

There is no public administrator-registration endpoint and registration requests contain no role field. Unknown JSON properties cannot grant roles.

## Continue with Google

Web, Android, or iOS authenticates with Google client-side and sends the resulting **ID token** to `POST /api/v1/auth/google`. The backend verifies signature, issuer, expiry, configured audience and `email_verified`, then returns the normal `AuthResponse`. Clients must use the returned platform access token for subsequent API calls.

For a new identity, `requestedRole` must be `CUSTOMER` or `SERVICE_PROVIDER`; `ADMIN` is never allowed. Google-only accounts have no password hash, so password login fails with the same generic invalid-credentials response. Google email verification sets only `emailVerified`; it does not verify a phone or approve marketplace participation.

If the verified email already belongs to a native account, implicit merging is refused with `identity.google_account_link_required`. After native login, link explicitly through `/api/v1/me/auth-identities/google`. Linking requires the Google verified email to match the platform email and cannot change roles.

## Register

Customer:

```http
POST /api/v1/auth/register/customers
Content-Type: application/json

{
  "email": "customer@example.test",
  "phone": "+251911234567",
  "password": "replace-with-a-strong-password"
}
```

Provider registration uses the same body at `/api/v1/auth/register/service-providers`. Phone is optional and, when provided, must use E.164 format. Password length is 12–128 characters.

Successful registration returns HTTP `201`:

```json
{
  "tokenType": "Bearer",
  "accessToken": "<jwt>",
  "accessTokenExpiresAt": "2026-11-10T18:00:00Z",
  "refreshToken": "<opaque-one-time-token>",
  "refreshTokenExpiresAt": "2026-12-10T17:45:00Z",
  "user": {
    "id": "00000000-0000-0000-0000-000000000001",
    "email": "customer@example.test",
    "status": "ACTIVE",
    "roles": ["CUSTOMER"]
  }
}
```

Passwords are BCrypt-hashed. Refresh tokens are never stored directly; PostgreSQL stores a SHA-256 fingerprint.

## Login and access tokens

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "customer@example.test",
  "password": "replace-with-a-strong-password"
}
```

Use the returned access token on protected requests:

```http
Authorization: Bearer <access-token>
```

Access tokens contain account ID, roles, session ID, issuer, expiration, account status and credential version. Protected requests re-read current account state; suspended, deactivated, deleted, or credential-version-invalid accounts are rejected even if a JWT has not expired.

## `/me`

`GET /api/v1/me` derives identity only from the validated JWT subject. The client cannot supply a user ID. The response includes account/contact-verification and marketplace-verification state but never password hashes, refresh-token hashes, or refresh-token secrets.

## Refresh rotation

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "<current-refresh-token>" }
```

Refresh is one-time rotation. A successful call revokes the supplied session and returns a new access token and refresh token. Reusing the old token returns `identity.invalid_refresh_token`. Expired, revoked, malformed, credential-version-mismatched, or inactive-account sessions are also rejected.

## Logout

```http
POST /api/v1/auth/logout
Authorization: Bearer <access-token>
```

Logout returns HTTP `204` and revokes the refresh session identified by the JWT `sid` claim. The revoked refresh token cannot be used again. The already-issued access JWT remains structurally valid until expiry unless account state or credential version invalidates it.

## Account status and roles

Account statuses are `ACTIVE`, `SUSPENDED`, and `DEACTIVATED`. Only `ACTIVE` accounts may log in, refresh, or use authenticated endpoints.

Roles are `CUSTOMER`, `SERVICE_PROVIDER`, and `ADMIN`. Controllers enforce role boundaries with method security. Administrator accounts are provisioned operationally, never through public registration.

Authentication/contact ownership and marketplace verification are different concepts. A valid login does not mean a customer/provider is marketplace approved.

## Common errors

All errors use `application/problem+json` and include a stable `code` and correlation `requestId` when available.

| Status | Code | Meaning |
| --- | --- | --- |
| `400` | `request.validation_failed` | Registration/login fields fail validation |
| `400` | `request.invalid_body` | JSON is missing or malformed |
| `401` | `identity.invalid_credentials` | Email/password is incorrect or unknown |
| `401` | `identity.account_inactive` | Account is suspended or deactivated |
| `401` | `identity.invalid_refresh_token` | Refresh session is invalid, expired, reused, or revoked |
| `401` | `security.authentication_required` | JWT is absent, invalid, expired, or account state is no longer valid |
| `403` | `security.access_denied` | JWT is valid but its role cannot perform the operation |
| `409` | `identity.contact_already_registered` | Email or phone is already registered |

## Required secrets

`JWT_SECRET_BASE64` must be supplied at runtime and decode to at least 32 random bytes. Generate a local value in PowerShell:

```powershell
$bytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
```

Never commit JWT keys, passwords, access tokens, refresh tokens, or payment webhook secrets.

`GOOGLE_ALLOWED_CLIENT_IDS` is an optional comma-separated allowlist for web, Android and iOS OAuth client IDs. When blank, native authentication remains available and the Google endpoint returns `identity.google_auth_unavailable`.
