package com.skilledservices.platform.profiles.domain

import java.time.Instant
import java.util.UUID

enum class MarketplaceSubjectType { CUSTOMER, PROVIDER }
enum class MarketplaceVerificationStatus { PENDING, APPROVED, REJECTED }
enum class VerificationDecisionValue { APPROVED, REJECTED }

data class MarketplaceVerification(
    val id: UUID,
    val accountId: UUID,
    val subjectType: MarketplaceSubjectType,
    val status: MarketplaceVerificationStatus,
    val submittedAt: Instant?,
    val reviewedAt: Instant?,
    val reviewerAdminId: UUID?,
    val decisionNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VerificationSubmission(
    val id: UUID,
    val verificationId: UUID,
    val submittedAt: Instant,
)

data class VerificationEvidence(
    val id: UUID,
    val submissionId: UUID,
    val identifier: String,
    val type: String,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val protectedObjectId: UUID,
    val uploadedAt: Instant,
)

data class VerificationDecision(
    val id: UUID,
    val verificationId: UUID,
    val resultingStatus: MarketplaceVerificationStatus,
    val reviewerAdminId: UUID,
    val reason: String,
    val decidedAt: Instant,
)

data class VerificationPage(
    val items: List<MarketplaceVerification>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

interface MarketplaceVerificationStore {
    fun save(verification: MarketplaceVerification): MarketplaceVerification
    fun findByAccountId(accountId: UUID): List<MarketplaceVerification>
    fun findByAccountIdAndSubjectType(accountId: UUID, subjectType: MarketplaceSubjectType): MarketplaceVerification?
    fun findApprovedAccountIds(accountIds: Set<UUID>, subjectType: MarketplaceSubjectType): Set<UUID> =
        accountIds.filterTo(mutableSetOf()) {
            findByAccountIdAndSubjectType(it, subjectType)?.status == MarketplaceVerificationStatus.APPROVED
        }
    fun findById(id: UUID): MarketplaceVerification?
    fun findByIdForUpdate(id: UUID): MarketplaceVerification?
    fun findAll(
        subjectType: MarketplaceSubjectType?,
        status: MarketplaceVerificationStatus?,
        page: Int,
        size: Int,
    ): VerificationPage
}

interface VerificationSubmissionStore {
    fun save(submission: VerificationSubmission): VerificationSubmission
    fun findById(id: UUID): VerificationSubmission?
    fun findByVerificationId(verificationId: UUID): List<VerificationSubmission>
}

interface VerificationEvidenceStore {
    fun saveAll(evidence: List<VerificationEvidence>): List<VerificationEvidence>
    fun findById(id: UUID): VerificationEvidence?
    fun findBySubmissionId(submissionId: UUID): List<VerificationEvidence>
}

interface VerificationDecisionStore {
    fun save(decision: VerificationDecision): VerificationDecision
    fun findByVerificationId(verificationId: UUID): List<VerificationDecision>
}
