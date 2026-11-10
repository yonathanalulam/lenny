package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.profiles.domain.CustomerProfileStore
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStore
import com.skilledservices.platform.profiles.domain.ProfileRoleRequiredException
import com.skilledservices.platform.profiles.domain.ProviderProfileStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class OnboardingStatus(
    val accountStatus: AccountStatus,
    val role: AccountRole,
    val setupComplete: Boolean,
    val verificationStatus: MarketplaceVerificationStatus,
    val verificationSubmittedAt: Instant?,
    val marketplaceEligible: Boolean,
)

@Service
class OnboardingService(
    private val identityAccountQuery: IdentityAccountQuery,
    private val customerProfileStore: CustomerProfileStore,
    private val providerProfileStore: ProviderProfileStore,
    private val verificationStore: MarketplaceVerificationStore,
) {
    @Transactional(readOnly = true)
    fun customer(accountId: UUID): OnboardingStatus = status(accountId, AccountRole.CUSTOMER, MarketplaceSubjectType.CUSTOMER)

    @Transactional(readOnly = true)
    fun provider(accountId: UUID): OnboardingStatus =
        status(accountId, AccountRole.SERVICE_PROVIDER, MarketplaceSubjectType.PROVIDER)

    private fun status(accountId: UUID, role: AccountRole, subjectType: MarketplaceSubjectType): OnboardingStatus {
        if (!identityAccountQuery.hasRole(accountId, role)) throw ProfileRoleRequiredException()
        val accountStatus = identityAccountQuery.accountStatus(accountId) ?: throw ProfileRoleRequiredException()
        val verification = verificationStore.findByAccountIdAndSubjectType(accountId, subjectType)
            ?: throw com.skilledservices.platform.profiles.domain.VerificationNotFoundException()
        val setupComplete = when (role) {
            AccountRole.CUSTOMER -> customerProfileStore.findByAccountId(accountId)?.setupComplete == true
            AccountRole.SERVICE_PROVIDER -> providerProfileStore.findByAccountId(accountId)?.setupComplete == true
            AccountRole.ADMIN -> false
        }
        return OnboardingStatus(
            accountStatus = accountStatus,
            role = role,
            setupComplete = setupComplete,
            verificationStatus = verification.status,
            verificationSubmittedAt = verification.submittedAt,
            marketplaceEligible = verification.status == MarketplaceVerificationStatus.APPROVED,
        )
    }
}
