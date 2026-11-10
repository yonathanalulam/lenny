package com.skilledservices.platform.profiles.infrastructure.persistence

import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStore
import com.skilledservices.platform.profiles.domain.VerificationDecision
import com.skilledservices.platform.profiles.domain.VerificationDecisionStore
import com.skilledservices.platform.profiles.domain.VerificationEvidence
import com.skilledservices.platform.profiles.domain.VerificationEvidenceStore
import com.skilledservices.platform.profiles.domain.VerificationPage
import com.skilledservices.platform.profiles.domain.VerificationSubmission
import com.skilledservices.platform.profiles.domain.VerificationSubmissionStore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "marketplace_verifications")
class MarketplaceVerificationEntity(
    @Id var id: UUID,
    @Column(name = "account_id", nullable = false) var accountId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_role", nullable = false, length = 32) var subjectType: MarketplaceSubjectType,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32) var status: MarketplaceVerificationStatus,
    @Column(name = "submitted_at") var submittedAt: Instant?,
    @Column(name = "reviewed_at") var reviewedAt: Instant?,
    @Column(name = "reviewer_admin_id") var reviewerAdminId: UUID?,
    @Column(name = "decision_note", length = 2000) var decisionNote: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "verification_submissions")
class VerificationSubmissionEntity(
    @Id var id: UUID,
    @Column(name = "verification_id", nullable = false) var verificationId: UUID,
    @Column(name = "submitted_at", nullable = false) var submittedAt: Instant,
)

@Entity
@Table(name = "verification_evidence")
class VerificationEvidenceEntity(
    @Id var id: UUID,
    @Column(name = "submission_id", nullable = false) var submissionId: UUID,
    @Column(name = "evidence_identifier", nullable = false, length = 100) var identifier: String,
    @Column(name = "evidence_type", nullable = false, length = 100) var type: String,
    @Column(name = "original_filename", nullable = false, length = 255) var originalFilename: String,
    @Column(name = "content_type", nullable = false, length = 100) var contentType: String,
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long,
    @Column(name = "protected_object_id", nullable = false) var protectedObjectId: UUID,
    @Column(name = "uploaded_at", nullable = false) var uploadedAt: Instant,
)

@Entity
@Table(name = "verification_decisions")
class VerificationDecisionEntity(
    @Id var id: UUID,
    @Column(name = "verification_id", nullable = false) var verificationId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_status", nullable = false, length = 32)
    var resultingStatus: MarketplaceVerificationStatus,
    @Column(name = "reviewer_admin_id", nullable = false) var reviewerAdminId: UUID,
    @Column(nullable = false, length = 2000) var reason: String,
    @Column(name = "decided_at", nullable = false) var decidedAt: Instant,
)

interface MarketplaceVerificationJpaRepository : JpaRepository<MarketplaceVerificationEntity, UUID> {
    fun findAllByAccountIdOrderBySubjectType(accountId: UUID): List<MarketplaceVerificationEntity>
    fun findByAccountIdAndSubjectType(accountId: UUID, subjectType: MarketplaceSubjectType): MarketplaceVerificationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from MarketplaceVerificationEntity v where v.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): MarketplaceVerificationEntity?

    fun findAllBySubmittedAtIsNotNullOrderByUpdatedAtDesc(page: Pageable): Page<MarketplaceVerificationEntity>
    fun findAllBySubjectTypeAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(subjectType: MarketplaceSubjectType, page: Pageable): Page<MarketplaceVerificationEntity>
    fun findAllByStatusAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(status: MarketplaceVerificationStatus, page: Pageable): Page<MarketplaceVerificationEntity>
    fun findAllBySubjectTypeAndStatusAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(
        subjectType: MarketplaceSubjectType,
        status: MarketplaceVerificationStatus,
        page: Pageable,
    ): Page<MarketplaceVerificationEntity>
}

interface VerificationSubmissionJpaRepository : JpaRepository<VerificationSubmissionEntity, UUID> {
    fun findAllByVerificationIdOrderBySubmittedAtDesc(verificationId: UUID): List<VerificationSubmissionEntity>
}

interface VerificationEvidenceJpaRepository : JpaRepository<VerificationEvidenceEntity, UUID> {
    fun findAllBySubmissionIdOrderByUploadedAt(submissionId: UUID): List<VerificationEvidenceEntity>
}

interface VerificationDecisionJpaRepository : JpaRepository<VerificationDecisionEntity, UUID> {
    fun findAllByVerificationIdOrderByDecidedAtDesc(verificationId: UUID): List<VerificationDecisionEntity>
}

@Repository
class JpaMarketplaceVerificationStore(private val repository: MarketplaceVerificationJpaRepository) : MarketplaceVerificationStore {
    override fun save(verification: MarketplaceVerification) = repository.save(verification.toEntity()).toDomain()
    override fun findByAccountId(accountId: UUID) = repository.findAllByAccountIdOrderBySubjectType(accountId).map { it.toDomain() }
    override fun findByAccountIdAndSubjectType(accountId: UUID, subjectType: MarketplaceSubjectType) =
        repository.findByAccountIdAndSubjectType(accountId, subjectType)?.toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()

    override fun findAll(
        subjectType: MarketplaceSubjectType?,
        status: MarketplaceVerificationStatus?,
        page: Int,
        size: Int,
    ): VerificationPage {
        val pageable = PageRequest.of(page, size)
        val result = when {
            subjectType != null && status != null -> repository.findAllBySubjectTypeAndStatusAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(subjectType, status, pageable)
            subjectType != null -> repository.findAllBySubjectTypeAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(subjectType, pageable)
            status != null -> repository.findAllByStatusAndSubmittedAtIsNotNullOrderByUpdatedAtDesc(status, pageable)
            else -> repository.findAllBySubmittedAtIsNotNullOrderByUpdatedAtDesc(pageable)
        }
        return VerificationPage(result.content.map { it.toDomain() }, page, size, result.totalElements, result.totalPages)
    }
}

@Repository
class JpaVerificationSubmissionStore(private val repository: VerificationSubmissionJpaRepository) : VerificationSubmissionStore {
    override fun save(submission: VerificationSubmission) = repository.save(submission.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByVerificationId(verificationId: UUID) =
        repository.findAllByVerificationIdOrderBySubmittedAtDesc(verificationId).map { it.toDomain() }
}

@Repository
class JpaVerificationEvidenceStore(private val repository: VerificationEvidenceJpaRepository) : VerificationEvidenceStore {
    override fun saveAll(evidence: List<VerificationEvidence>) = repository.saveAll(evidence.map { it.toEntity() }).map { it.toDomain() }
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findBySubmissionId(submissionId: UUID) =
        repository.findAllBySubmissionIdOrderByUploadedAt(submissionId).map { it.toDomain() }
}

@Repository
class JpaVerificationDecisionStore(private val repository: VerificationDecisionJpaRepository) : VerificationDecisionStore {
    override fun save(decision: VerificationDecision) = repository.save(decision.toEntity()).toDomain()
    override fun findByVerificationId(verificationId: UUID) =
        repository.findAllByVerificationIdOrderByDecidedAtDesc(verificationId).map { it.toDomain() }
}

private fun MarketplaceVerificationEntity.toDomain() = MarketplaceVerification(
    id, accountId, subjectType, status, submittedAt, reviewedAt, reviewerAdminId, decisionNote, createdAt, updatedAt,
)
private fun MarketplaceVerification.toEntity() = MarketplaceVerificationEntity(
    id, accountId, subjectType, status, submittedAt, reviewedAt, reviewerAdminId, decisionNote, createdAt, updatedAt,
)
private fun VerificationSubmissionEntity.toDomain() = VerificationSubmission(id, verificationId, submittedAt)
private fun VerificationSubmission.toEntity() = VerificationSubmissionEntity(id, verificationId, submittedAt)
private fun VerificationEvidenceEntity.toDomain() = VerificationEvidence(
    id, submissionId, identifier, type, originalFilename, contentType, sizeBytes, protectedObjectId, uploadedAt,
)
private fun VerificationEvidence.toEntity() = VerificationEvidenceEntity(
    id, submissionId, identifier, type, originalFilename, contentType, sizeBytes, protectedObjectId, uploadedAt,
)
private fun VerificationDecisionEntity.toDomain() = VerificationDecision(
    id, verificationId, resultingStatus, reviewerAdminId, reason, decidedAt,
)
private fun VerificationDecision.toEntity() = VerificationDecisionEntity(
    id, verificationId, resultingStatus, reviewerAdminId, reason, decidedAt,
)
