package com.skilledservices.platform.identity.domain

import java.time.Instant
import java.time.Duration
import java.util.UUID

interface AccountStore {
    fun existsByEmail(email: String): Boolean
    fun existsByPhone(phone: String): Boolean
    fun findByEmail(email: String): Account?
    fun findById(id: UUID): Account?
    fun save(account: Account): Account
}

interface ExternalIdentityStore {
    fun findByProviderAndSubject(provider: ExternalIdentityProvider, subject: String): ExternalIdentity?
    fun findByAccountAndProvider(accountId: UUID, provider: ExternalIdentityProvider): ExternalIdentity?
    fun save(identity: ExternalIdentity): ExternalIdentity
}

interface RefreshSessionStore {
    fun save(session: RefreshSession): RefreshSession
    fun findByIdForUpdate(id: UUID): RefreshSession?
    fun revokeAllForAccount(accountId: UUID, revokedAt: Instant): Int
}

interface PasswordHashPort {
    fun hash(rawPassword: String): String
    fun matches(rawPassword: String, encodedPassword: String): Boolean
}

fun interface AccessTokenPort {
    fun issue(account: Account, sessionId: UUID, issuedAt: Instant): IssuedAccessToken
}

interface SessionPolicy {
    val refreshTokenTtl: Duration
}

data class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant,
)

/**
 * Boundary for a future email/SMS provider. The identity slice intentionally does not fake delivery.
 */
interface ContactVerificationDeliveryPort {
    fun sendEmailOwnershipChallenge(accountId: UUID, email: String, challenge: String)
    fun sendPhoneOwnershipChallenge(accountId: UUID, phone: String, challenge: String)
}
