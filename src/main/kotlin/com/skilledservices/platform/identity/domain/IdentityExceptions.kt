package com.skilledservices.platform.identity.domain

open class IdentityException(
    message: String,
    val code: String,
) : RuntimeException(message)

class DuplicateContactException(field: String) :
    IdentityException("An account already uses this $field", "identity.contact_already_registered")

class InvalidCredentialsException :
    IdentityException("The supplied credentials are invalid", "identity.invalid_credentials")

class InvalidRefreshTokenException :
    IdentityException("The refresh session is invalid or expired", "identity.invalid_refresh_token")

class InactiveAccountException :
    IdentityException("The account is not active", "identity.account_inactive")

class AccountNotFoundException :
    IdentityException("The authenticated account no longer exists", "identity.account_not_found")

class AccountStatusChangeInvalidException(message: String) :
    IdentityException(message, "identity.account_status_change_invalid")

class ContactEmailMismatchException :
    IdentityException(
        "Profile email must match the authenticated account; use the verified email-change flow to change it",
        "identity.contact_email_mismatch",
    )

class GoogleAuthUnavailableException : IdentityException("Google authentication is not configured", "identity.google_auth_unavailable")
class GoogleTokenInvalidException : IdentityException("The Google ID token is invalid", "identity.google_token_invalid")
class GoogleTokenExpiredException : IdentityException("The Google ID token is expired", "identity.google_token_expired")
class GoogleAudienceInvalidException : IdentityException("The Google ID token audience is not allowed", "identity.google_audience_invalid")
class GoogleEmailNotVerifiedException : IdentityException("Google has not verified this email", "identity.google_email_not_verified")
class GoogleAccountLinkRequiredException : IdentityException("Authenticate to the existing account and explicitly link Google", "identity.google_account_link_required")
class GoogleIdentityAlreadyLinkedException : IdentityException("This Google identity or account is already linked", "identity.google_identity_already_linked")
class GoogleRegistrationRoleRequiredException : IdentityException("A CUSTOMER or SERVICE_PROVIDER role is required for first-time Google registration", "identity.google_registration_role_required")
class GoogleIdentityEmailMismatchException : IdentityException("Google verified email must match the platform account email", "identity.google_identity_email_mismatch")
