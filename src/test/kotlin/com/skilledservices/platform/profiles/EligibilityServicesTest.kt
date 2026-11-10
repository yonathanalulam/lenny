package com.skilledservices.platform.profiles

import com.skilledservices.platform.profiles.application.VerificationBackedCustomerEligibilityService
import com.skilledservices.platform.profiles.application.VerificationBackedProviderEligibilityService
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStore
import com.skilledservices.platform.profiles.domain.VerificationPage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class EligibilityServicesTest {
    @ParameterizedTest
    @EnumSource(MarketplaceVerificationStatus::class)
    fun `customer eligibility is granted only when approved`(status: MarketplaceVerificationStatus) {
        val accountId = UUID.randomUUID()
        val service = VerificationBackedCustomerEligibilityService(
            SingleVerificationStore(verification(accountId, MarketplaceSubjectType.CUSTOMER, status)),
        )

        assertEquals(status == MarketplaceVerificationStatus.APPROVED, service.canCreateMarketplaceRequest(accountId))
    }

    @ParameterizedTest
    @EnumSource(MarketplaceVerificationStatus::class)
    fun `provider eligibility is granted only when approved`(status: MarketplaceVerificationStatus) {
        val accountId = UUID.randomUUID()
        val service = VerificationBackedProviderEligibilityService(
            SingleVerificationStore(verification(accountId, MarketplaceSubjectType.PROVIDER, status)),
        )

        assertEquals(status == MarketplaceVerificationStatus.APPROVED, service.isMarketplaceEligible(accountId))
    }

    private fun verification(
        accountId: UUID,
        subjectType: MarketplaceSubjectType,
        status: MarketplaceVerificationStatus,
    ): MarketplaceVerification {
        val now = Instant.parse("2026-11-06T00:00:00Z")
        val decided = status != MarketplaceVerificationStatus.PENDING
        return MarketplaceVerification(
            UUID.randomUUID(), accountId, subjectType, status, now,
            now.takeIf { decided }, UUID.randomUUID().takeIf { decided }, "test decision".takeIf { decided }, now, now,
        )
    }
}

private class SingleVerificationStore(
    private var verification: MarketplaceVerification,
) : MarketplaceVerificationStore {
    override fun save(verification: MarketplaceVerification) = verification.also { this.verification = it }
    override fun findByAccountId(accountId: UUID) = listOfNotNull(verification.takeIf { it.accountId == accountId })
    override fun findByAccountIdAndSubjectType(accountId: UUID, subjectType: MarketplaceSubjectType) =
        verification.takeIf { it.accountId == accountId && it.subjectType == subjectType }
    override fun findById(id: UUID) = verification.takeIf { it.id == id }
    override fun findByIdForUpdate(id: UUID) = findById(id)
    override fun findAll(
        subjectType: MarketplaceSubjectType?,
        status: MarketplaceVerificationStatus?,
        page: Int,
        size: Int,
    ) = VerificationPage(listOf(verification), page, size, 1, 1)
}
