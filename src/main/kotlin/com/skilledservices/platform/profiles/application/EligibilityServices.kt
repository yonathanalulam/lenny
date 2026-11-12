package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface CustomerEligibilityService {
    fun canCreateMarketplaceRequest(userId: UUID): Boolean
}

interface ProviderEligibilityService {
    fun isMarketplaceEligible(userId: UUID): Boolean
    fun eligibleProviderIds(userIds: Set<UUID>): Set<UUID>
}

@Service
class VerificationBackedCustomerEligibilityService(
    private val store: MarketplaceVerificationStore,
) : CustomerEligibilityService {
    @Transactional(readOnly = true)
    override fun canCreateMarketplaceRequest(userId: UUID): Boolean =
        store.findByAccountIdAndSubjectType(userId, MarketplaceSubjectType.CUSTOMER)?.status ==
            MarketplaceVerificationStatus.APPROVED
}

@Service
class VerificationBackedProviderEligibilityService(
    private val store: MarketplaceVerificationStore,
) : ProviderEligibilityService {
    @Transactional(readOnly = true)
    override fun isMarketplaceEligible(userId: UUID): Boolean =
        store.findByAccountIdAndSubjectType(userId, MarketplaceSubjectType.PROVIDER)?.status ==
            MarketplaceVerificationStatus.APPROVED

    @Transactional(readOnly = true)
    override fun eligibleProviderIds(userIds: Set<UUID>): Set<UUID> =
        store.findApprovedAccountIds(userIds, MarketplaceSubjectType.PROVIDER)
}
