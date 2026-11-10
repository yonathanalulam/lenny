package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import java.time.Instant
import java.util.UUID

interface VerificationRegistrationPort {
    fun initializePending(accountId: UUID, subjectType: MarketplaceSubjectType, now: Instant): MarketplaceVerification
    fun findByAccountId(accountId: UUID): List<MarketplaceVerification>
}
