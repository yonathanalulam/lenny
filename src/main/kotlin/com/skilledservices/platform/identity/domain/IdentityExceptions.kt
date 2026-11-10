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
