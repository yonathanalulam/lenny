package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.AccessTokenPort
import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.identity.domain.DuplicateContactException
import com.skilledservices.platform.identity.domain.InactiveAccountException
import com.skilledservices.platform.identity.domain.InvalidCredentialsException
import com.skilledservices.platform.identity.domain.InvalidRefreshTokenException
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.identity.domain.PasswordHashPort
import com.skilledservices.platform.identity.domain.RefreshSession
import com.skilledservices.platform.identity.domain.RefreshSessionStore
import com.skilledservices.platform.identity.domain.SessionPolicy
import com.skilledservices.platform.identity.domain.ExternalIdentity
import com.skilledservices.platform.identity.domain.ExternalIdentityProvider
import com.skilledservices.platform.identity.domain.ExternalIdentityStore
import com.skilledservices.platform.identity.domain.GoogleAccountLinkRequiredException
import com.skilledservices.platform.identity.domain.GoogleIdentityAlreadyLinkedException
import com.skilledservices.platform.identity.domain.GoogleIdentityEmailMismatchException
import com.skilledservices.platform.identity.domain.GoogleRegistrationRoleRequiredException
import com.skilledservices.platform.identity.domain.AccountNotFoundException
import com.skilledservices.platform.profiles.application.VerificationRegistrationPort
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class AuthService(
    private val accountStore: AccountStore,
    private val verificationTrustService: VerificationRegistrationPort,
    private val refreshSessionStore: RefreshSessionStore,
    private val externalIdentityStore: ExternalIdentityStore,
    private val googleIdentityVerifier: GoogleIdentityVerifier,
    private val passwordHashPort: PasswordHashPort,
    private val accessTokenPort: AccessTokenPort,
    private val sessionPolicy: SessionPolicy,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun registerCustomer(command: RegistrationCommand, context: ClientContext): AuthResult =
        register(command, AccountRole.CUSTOMER, context)

    @Transactional
    fun registerServiceProvider(command: RegistrationCommand, context: ClientContext): AuthResult =
        register(command, AccountRole.SERVICE_PROVIDER, context)

    @Transactional
    fun login(command: LoginCommand, context: ClientContext): AuthResult {
        val email = normalizeEmail(command.email)
        val account = accountStore.findByEmail(email)
            ?: throw InvalidCredentialsException()
        val passwordHash = account.passwordHash
        if (passwordHash == null || !passwordHashPort.matches(command.password, passwordHash)) {
            throw InvalidCredentialsException()
        }
        ensureActive(account)
        return createAuthenticatedSession(account, context)
    }

    @Transactional
    fun authenticateWithGoogle(command: GoogleAuthCommand, context: ClientContext): AuthResult {
        val verified = googleIdentityVerifier.verifyIdToken(command.idToken)
        val now = clock.instant()
        val linked = externalIdentityStore.findByProviderAndSubject(ExternalIdentityProvider.GOOGLE, verified.subject)
        if (linked != null) {
            val account = accountStore.findById(linked.accountId) ?: throw InvalidCredentialsException()
            ensureActive(account)
            externalIdentityStore.save(linked.copy(verifiedEmail = verified.email, lastLoginAt = now))
            return createAuthenticatedSession(account, context)
        }
        if (accountStore.findByEmail(normalizeEmail(verified.email)) != null) throw GoogleAccountLinkRequiredException()
        val role = command.requestedRole
        if (role !in setOf(AccountRole.CUSTOMER, AccountRole.SERVICE_PROVIDER)) throw GoogleRegistrationRoleRequiredException()
        val phone = command.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null && accountStore.existsByPhone(phone)) throw DuplicateContactException("phone number")
        val account = accountStore.save(Account(
            id = UUID.randomUUID(), email = normalizeEmail(verified.email), phone = phone, passwordHash = null,
            status = AccountStatus.ACTIVE, roles = setOf(role!!), emailVerified = true, phoneVerified = false,
            credentialVersion = 1, createdAt = now, updatedAt = now,
        ))
        verificationTrustService.initializePending(account.id, role.toMarketplaceSubjectType(), now)
        externalIdentityStore.save(ExternalIdentity(
            UUID.randomUUID(), account.id, ExternalIdentityProvider.GOOGLE, verified.subject,
            verified.email, now, now,
        ))
        return createAuthenticatedSession(account, context)
    }

    @Transactional
    fun linkGoogle(accountId: UUID, idToken: String): GoogleLinkResult {
        val account = accountStore.findById(accountId) ?: throw AccountNotFoundException()
        ensureActive(account)
        val verified = googleIdentityVerifier.verifyIdToken(idToken)
        if (!account.email.equals(verified.email, ignoreCase = true)) throw GoogleIdentityEmailMismatchException()
        if (externalIdentityStore.findByProviderAndSubject(ExternalIdentityProvider.GOOGLE, verified.subject) != null ||
            externalIdentityStore.findByAccountAndProvider(accountId, ExternalIdentityProvider.GOOGLE) != null
        ) throw GoogleIdentityAlreadyLinkedException()
        val now = clock.instant()
        return GoogleLinkResult(externalIdentityStore.save(ExternalIdentity(
            UUID.randomUUID(), account.id, ExternalIdentityProvider.GOOGLE, verified.subject,
            verified.email, now, now,
        )))
    }

    @Transactional
    fun refresh(rawRefreshToken: String, context: ClientContext): AuthResult {
        val sessionId = rawRefreshToken.substringBefore('.', "").let {
            runCatching { UUID.fromString(it) }.getOrElse { throw InvalidRefreshTokenException() }
        }
        val now = clock.instant()
        val session = refreshSessionStore.findByIdForUpdate(sessionId)
            ?: throw InvalidRefreshTokenException()
        if (!session.isUsableAt(now) || !constantTimeEquals(session.tokenHash, hashToken(rawRefreshToken))) {
            throw InvalidRefreshTokenException()
        }

        val account = accountStore.findById(session.accountId) ?: throw InvalidRefreshTokenException()
        ensureActive(account)
        if (session.credentialVersion != account.credentialVersion) {
            throw InvalidRefreshTokenException()
        }

        refreshSessionStore.save(session.copy(lastUsedAt = now, revokedAt = now))
        return createAuthenticatedSession(account, context)
    }

    @Transactional
    fun logout(sessionId: UUID, accountId: UUID) {
        val now = clock.instant()
        val session = refreshSessionStore.findByIdForUpdate(sessionId) ?: return
        if (session.accountId == accountId && session.revokedAt == null) {
            refreshSessionStore.save(session.copy(revokedAt = now))
        }
    }

    private fun register(
        command: RegistrationCommand,
        role: AccountRole,
        context: ClientContext,
    ): AuthResult {
        val email = normalizeEmail(command.email)
        val phone = command.phone?.trim()?.takeIf { it.isNotEmpty() }
        if (accountStore.existsByEmail(email)) throw DuplicateContactException("email")
        if (phone != null && accountStore.existsByPhone(phone)) throw DuplicateContactException("phone number")

        val now = clock.instant()
        val account = accountStore.save(
            Account(
                id = UUID.randomUUID(),
                email = email,
                phone = phone,
                passwordHash = passwordHashPort.hash(command.password),
                status = AccountStatus.ACTIVE,
                roles = setOf(role),
                emailVerified = false,
                phoneVerified = false,
                credentialVersion = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        verificationTrustService.initializePending(
            accountId = account.id,
            subjectType = role.toMarketplaceSubjectType(),
            now = now,
        )
        return createAuthenticatedSession(account, context)
    }

    private fun createAuthenticatedSession(account: Account, context: ClientContext): AuthResult {
        val now = clock.instant()
        val sessionId = UUID.randomUUID()
        val rawRefreshToken = "$sessionId.${randomSecret()}"
        val refreshExpiresAt = now.plus(sessionPolicy.refreshTokenTtl)
        refreshSessionStore.save(
            RefreshSession(
                id = sessionId,
                accountId = account.id,
                tokenHash = hashToken(rawRefreshToken),
                credentialVersion = account.credentialVersion,
                userAgent = context.userAgent?.take(512),
                ipAddress = context.ipAddress?.take(64),
                createdAt = now,
                expiresAt = refreshExpiresAt,
                lastUsedAt = null,
                revokedAt = null,
            ),
        )
        val accessToken = accessTokenPort.issue(account, sessionId, now)
        return AuthResult(
            account = account,
            verifications = verificationTrustService.findByAccountId(account.id),
            accessToken = accessToken.value,
            accessTokenExpiresAt = accessToken.expiresAt,
            refreshToken = rawRefreshToken,
            refreshTokenExpiresAt = refreshExpiresAt,
        )
    }

    private fun ensureActive(account: Account) {
        if (account.status != AccountStatus.ACTIVE) throw InactiveAccountException()
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun AccountRole.toMarketplaceSubjectType(): MarketplaceSubjectType = when (this) {
        AccountRole.CUSTOMER -> MarketplaceSubjectType.CUSTOMER
        AccountRole.SERVICE_PROVIDER -> MarketplaceSubjectType.PROVIDER
        AccountRole.ADMIN -> error("Administrator accounts do not have public marketplace verification")
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )
}
