package com.skilledservices.platform.identity.infrastructure.persistence

import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.identity.domain.RefreshSession
import com.skilledservices.platform.identity.domain.RefreshSessionStore
import com.skilledservices.platform.identity.domain.ExternalIdentity
import com.skilledservices.platform.identity.domain.ExternalIdentityProvider
import com.skilledservices.platform.identity.domain.ExternalIdentityStore
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JpaAccountStore(
    private val repository: AccountJpaRepository,
) : AccountStore {
    override fun existsByEmail(email: String): Boolean = repository.existsByEmail(email)

    override fun existsByPhone(phone: String): Boolean = repository.existsByPhone(phone)

    override fun findByEmail(email: String): Account? = repository.findByEmail(email)?.toDomain()

    override fun findById(id: UUID): Account? = repository.findById(id).orElse(null)?.toDomain()

    override fun save(account: Account): Account = repository.save(account.toEntity()).toDomain()
}

@Repository
class JpaExternalIdentityStore(private val repository: ExternalIdentityJpaRepository) : ExternalIdentityStore {
    override fun findByProviderAndSubject(provider: ExternalIdentityProvider, subject: String) =
        repository.findByProviderAndProviderSubject(provider, subject)?.toDomain()
    override fun findByAccountAndProvider(accountId: UUID, provider: ExternalIdentityProvider) =
        repository.findByAccountIdAndProvider(accountId, provider)?.toDomain()
    override fun save(identity: ExternalIdentity) = repository.save(identity.toEntity()).toDomain()
}

@Repository
class JpaRefreshSessionStore(
    private val repository: RefreshSessionJpaRepository,
) : RefreshSessionStore {
    override fun save(session: RefreshSession): RefreshSession = repository.save(session.toEntity()).toDomain()

    override fun findByIdForUpdate(id: UUID): RefreshSession? = repository.findByIdForUpdate(id)?.toDomain()

    override fun revokeAllForAccount(accountId: UUID, revokedAt: Instant): Int =
        repository.revokeAllForAccount(accountId, revokedAt)
}

private fun AccountEntity.toDomain() = Account(
    id = id,
    email = email,
    phone = phone,
    passwordHash = passwordHash,
    status = status,
    roles = roles.toSet(),
    emailVerified = emailVerified,
    phoneVerified = phoneVerified,
    credentialVersion = credentialVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Account.toEntity() = AccountEntity(
    id = id,
    email = email,
    phone = phone,
    passwordHash = passwordHash,
    status = status,
    roles = roles.toMutableSet(),
    emailVerified = emailVerified,
    phoneVerified = phoneVerified,
    credentialVersion = credentialVersion,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ExternalIdentityEntity.toDomain() = ExternalIdentity(id, accountId, provider, providerSubject, verifiedEmail, createdAt, lastLoginAt)
private fun ExternalIdentity.toEntity() = ExternalIdentityEntity(id, accountId, provider, providerSubject, verifiedEmail, createdAt, lastLoginAt)

private fun RefreshSessionEntity.toDomain() = RefreshSession(
    id = id,
    accountId = accountId,
    tokenHash = tokenHash,
    credentialVersion = credentialVersion,
    userAgent = userAgent,
    ipAddress = ipAddress,
    createdAt = createdAt,
    expiresAt = expiresAt,
    lastUsedAt = lastUsedAt,
    revokedAt = revokedAt,
)

private fun RefreshSession.toEntity() = RefreshSessionEntity(
    id = id,
    accountId = accountId,
    tokenHash = tokenHash,
    credentialVersion = credentialVersion,
    userAgent = userAgent,
    ipAddress = ipAddress,
    createdAt = createdAt,
    expiresAt = expiresAt,
    lastUsedAt = lastUsedAt,
    revokedAt = revokedAt,
)
