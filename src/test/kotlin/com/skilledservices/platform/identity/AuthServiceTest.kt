package com.skilledservices.platform.identity

import com.skilledservices.platform.identity.application.AuthService
import com.skilledservices.platform.identity.application.ClientContext
import com.skilledservices.platform.identity.application.RegistrationCommand
import com.skilledservices.platform.identity.application.GoogleIdentityVerifier
import com.skilledservices.platform.identity.application.VerifiedGoogleIdentity
import com.skilledservices.platform.identity.domain.AccessTokenPort
import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.identity.domain.IssuedAccessToken
import com.skilledservices.platform.identity.domain.InvalidRefreshTokenException
import com.skilledservices.platform.identity.domain.PasswordHashPort
import com.skilledservices.platform.identity.domain.RefreshSession
import com.skilledservices.platform.identity.domain.RefreshSessionStore
import com.skilledservices.platform.identity.domain.SessionPolicy
import com.skilledservices.platform.identity.domain.ExternalIdentity
import com.skilledservices.platform.identity.domain.ExternalIdentityProvider
import com.skilledservices.platform.identity.domain.ExternalIdentityStore
import com.skilledservices.platform.profiles.application.VerificationRegistrationPort
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AuthServiceTest {
    private val now = Instant.parse("2026-11-04T10:00:00Z")
    private val accounts = InMemoryAccountStore()
    private val verifications = InMemoryVerificationStore()
    private val sessions = InMemoryRefreshSessionStore()
    private val service = AuthService(
        accountStore = accounts,
        verificationTrustService = verifications,
        refreshSessionStore = sessions,
        externalIdentityStore = InMemoryExternalIdentityStore(),
        googleIdentityVerifier = GoogleIdentityVerifier { VerifiedGoogleIdentity("unused", "unused@example.test") },
        passwordHashPort = PrefixPasswordHasher,
        accessTokenPort = AccessTokenPort { _, sessionId, issuedAt ->
            IssuedAccessToken("access-$sessionId", issuedAt.plusSeconds(900))
        },
        sessionPolicy = object : SessionPolicy {
            override val refreshTokenTtl: Duration = Duration.ofDays(30)
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `registration separates verification state and refresh tokens are one time`() {
        val registered = service.registerServiceProvider(
            RegistrationCommand(
                email = "  PROVIDER@Example.com ",
                phone = "+251911234567",
                password = "a-secure-password",
            ),
            ClientContext("unit-test", "127.0.0.1"),
        )

        assertEquals("provider@example.com", registered.account.email)
        assertEquals(setOf(AccountRole.SERVICE_PROVIDER), registered.account.roles)
        assertFalse(registered.account.emailVerified)
        assertFalse(registered.account.phoneVerified)
        assertEquals(MarketplaceVerificationStatus.PENDING, registered.verifications.single().status)
        assertNotEquals(registered.refreshToken, sessions.storedSessions.values.single().tokenHash)

        val rotated = service.refresh(registered.refreshToken, ClientContext(null, null))
        assertNotEquals(registered.refreshToken, rotated.refreshToken)
        assertFailsWith<InvalidRefreshTokenException> {
            service.refresh(registered.refreshToken, ClientContext(null, null))
        }
    }
}

private class InMemoryExternalIdentityStore : ExternalIdentityStore {
    private val values = linkedMapOf<UUID, ExternalIdentity>()
    override fun findByProviderAndSubject(provider: ExternalIdentityProvider, subject: String) = values.values.firstOrNull { it.provider == provider && it.providerSubject == subject }
    override fun findByAccountAndProvider(accountId: UUID, provider: ExternalIdentityProvider) = values.values.firstOrNull { it.accountId == accountId && it.provider == provider }
    override fun save(identity: ExternalIdentity) = identity.also { values[it.id] = it }
}

private class InMemoryAccountStore : AccountStore {
    private val values = linkedMapOf<UUID, Account>()

    override fun existsByEmail(email: String) = values.values.any { it.email == email }
    override fun existsByPhone(phone: String) = values.values.any { it.phone == phone }
    override fun findByEmail(email: String) = values.values.firstOrNull { it.email == email }
    override fun findById(id: UUID) = values[id]
    override fun save(account: Account): Account = account.also { values[it.id] = it }
}

private class InMemoryVerificationStore : VerificationRegistrationPort {
    private val values = linkedMapOf<UUID, MarketplaceVerification>()

    override fun initializePending(
        accountId: UUID,
        subjectType: MarketplaceSubjectType,
        now: Instant,
    ): MarketplaceVerification = MarketplaceVerification(
        id = UUID.randomUUID(),
        accountId = accountId,
        subjectType = subjectType,
        status = MarketplaceVerificationStatus.PENDING,
        submittedAt = null,
        reviewedAt = null,
        reviewerAdminId = null,
        decisionNote = null,
        createdAt = now,
        updatedAt = now,
    ).also { values[it.id] = it }

    override fun findByAccountId(accountId: UUID): List<MarketplaceVerification> =
        values.values.filter { it.accountId == accountId }
}

private class InMemoryRefreshSessionStore : RefreshSessionStore {
    val storedSessions = linkedMapOf<UUID, RefreshSession>()

    override fun save(session: RefreshSession): RefreshSession = session.also { storedSessions[it.id] = it }
    override fun findByIdForUpdate(id: UUID) = storedSessions[id]
    override fun revokeAllForAccount(accountId: UUID, revokedAt: Instant): Int {
        val matching = storedSessions.values.filter { it.accountId == accountId && it.revokedAt == null }
        matching.forEach { storedSessions[it.id] = it.copy(revokedAt = revokedAt) }
        return matching.size
    }
}

private object PrefixPasswordHasher : PasswordHashPort {
    override fun hash(rawPassword: String) = "hashed:$rawPassword"
    override fun matches(rawPassword: String, encodedPassword: String) = encodedPassword == hash(rawPassword)
}
