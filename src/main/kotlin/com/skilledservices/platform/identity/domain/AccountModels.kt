package com.skilledservices.platform.identity.domain

import java.time.Instant
import java.util.UUID

enum class AccountRole {
    CUSTOMER,
    SERVICE_PROVIDER,
    ADMIN,
}

enum class AccountStatus {
    ACTIVE,
    SUSPENDED,
    DEACTIVATED,
}

data class Account(
    val id: UUID,
    val email: String,
    val phone: String?,
    val passwordHash: String?,
    val status: AccountStatus,
    val roles: Set<AccountRole>,
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
    val credentialVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ExternalIdentityProvider { GOOGLE }

data class ExternalIdentity(
    val id: UUID,
    val accountId: UUID,
    val provider: ExternalIdentityProvider,
    val providerSubject: String,
    val verifiedEmail: String,
    val createdAt: Instant,
    val lastLoginAt: Instant,
)


data class RefreshSession(
    val id: UUID,
    val accountId: UUID,
    val tokenHash: String,
    val credentialVersion: Int,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
) {
    fun isUsableAt(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
