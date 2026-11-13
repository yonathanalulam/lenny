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

enum class SetupStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }

data class ContactVerificationStatus(
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
)

data class CustomerSetupRequirements(
    val fullNameProvided: Boolean,
    val serviceAreaSelected: Boolean,
    val preferredLanguageSelected: Boolean,
    val requiredContactVerified: Boolean,
) {
    val complete: Boolean
        get() = fullNameProvided && serviceAreaSelected && preferredLanguageSelected && requiredContactVerified
}

data class CustomerOnboardingStatus(
    val accountStatus: AccountStatus,
    val role: AccountRole,
    val setupStatus: SetupStatus,
    val setupComplete: Boolean,
    val marketplaceEligible: Boolean,
    val contactVerification: ContactVerificationStatus,
    val requirements: CustomerSetupRequirements,
    val verificationStatus: MarketplaceVerificationStatus,
    val verificationSubmittedAt: Instant?,
)

@Service
class OnboardingService(
    private val identityAccountQuery: IdentityAccountQuery,
    private val customerProfileStore: CustomerProfileStore,
    private val providerProfileStore: ProviderProfileStore,
    private val verificationStore: MarketplaceVerificationStore,
) {
    @Transactional(readOnly = true)
    fun customer(accountId: UUID): CustomerOnboardingStatus {
        if (!identityAccountQuery.hasRole(accountId, AccountRole.CUSTOMER)) throw ProfileRoleRequiredException()
        val accountStatus = identityAccountQuery.accountStatus(accountId) ?: throw ProfileRoleRequiredException()
        val contact = identityAccountQuery.accountContact(accountId) ?: throw ProfileRoleRequiredException()
        val profile = customerProfileStore.findByAccountId(accountId)
        val verification = verificationStore.findByAccountIdAndSubjectType(accountId, MarketplaceSubjectType.CUSTOMER)
            ?: throw com.skilledservices.platform.profiles.domain.VerificationNotFoundException()
        val requirements = CustomerSetupRequirements(
            fullNameProvided = !profile?.fullName.isNullOrBlank(),
            serviceAreaSelected = !profile?.serviceAreaId.isNullOrBlank(),
            preferredLanguageSelected = !profile?.preferredLanguage.isNullOrBlank(),
            requiredContactVerified = contact.requiredContactVerified,
        )
        val setupStatus = when {
            requirements.complete -> SetupStatus.COMPLETE
            requirements.fullNameProvided || requirements.serviceAreaSelected || requirements.preferredLanguageSelected ||
                requirements.requiredContactVerified -> SetupStatus.IN_PROGRESS
            else -> SetupStatus.NOT_STARTED
        }
        return CustomerOnboardingStatus(
            accountStatus = accountStatus,
            role = AccountRole.CUSTOMER,
            setupStatus = setupStatus,
            setupComplete = requirements.complete,
            marketplaceEligible = verification.status == MarketplaceVerificationStatus.APPROVED,
            contactVerification = ContactVerificationStatus(contact.emailVerified, contact.phoneVerified),
            requirements = requirements,
            verificationStatus = verification.status,
            verificationSubmittedAt = verification.submittedAt,
        )
    }

    @Transactional(readOnly = true)
    fun provider(accountId: UUID): OnboardingStatus =
        status(accountId, AccountRole.SERVICE_PROVIDER, MarketplaceSubjectType.PROVIDER)

    private fun status(accountId: UUID, role: AccountRole, subjectType: MarketplaceSubjectType): OnboardingStatus {
        if (!identityAccountQuery.hasRole(accountId, role)) throw ProfileRoleRequiredException()
        val accountStatus = identityAccountQuery.accountStatus(accountId) ?: throw ProfileRoleRequiredException()
        val verification = verificationStore.findByAccountIdAndSubjectType(accountId, subjectType)
            ?: throw com.skilledservices.platform.profiles.domain.VerificationNotFoundException()
        val setupComplete = when (role) {
            AccountRole.SERVICE_PROVIDER -> providerProfileStore.findByAccountId(accountId)?.setupComplete == true
            AccountRole.CUSTOMER, AccountRole.ADMIN -> false
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
