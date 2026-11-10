package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.profiles.domain.ForbiddenVerificationAccessException
import com.skilledservices.platform.profiles.domain.InvalidVerificationEvidenceException
import com.skilledservices.platform.profiles.domain.InvalidVerificationTransitionException
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStore
import com.skilledservices.platform.profiles.domain.ProfileRoleRequiredException
import com.skilledservices.platform.shared.storage.ProtectedObject
import com.skilledservices.platform.shared.storage.ProtectedObjectStorage
import com.skilledservices.platform.shared.storage.ProtectedObjectStore
import com.skilledservices.platform.shared.storage.ProtectedUploadPolicy
import com.skilledservices.platform.profiles.domain.UnsupportedUploadTypeException
import com.skilledservices.platform.profiles.domain.UploadTooLargeException
import com.skilledservices.platform.profiles.domain.VerificationAlreadyPendingException
import com.skilledservices.platform.profiles.domain.VerificationDecision
import com.skilledservices.platform.profiles.domain.VerificationDecisionStore
import com.skilledservices.platform.profiles.domain.VerificationDecisionValue
import com.skilledservices.platform.profiles.domain.VerificationEvidence
import com.skilledservices.platform.profiles.domain.VerificationEvidenceStore
import com.skilledservices.platform.profiles.domain.VerificationNotFoundException
import com.skilledservices.platform.profiles.domain.VerificationPage
import com.skilledservices.platform.profiles.domain.VerificationSubmission
import com.skilledservices.platform.profiles.domain.VerificationSubmissionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class EvidenceUpload(
    val identifier: String,
    val type: String,
    val originalFilename: String,
    val contentType: String,
    val content: ByteArray,
)

data class SubmissionWithEvidence(
    val submission: VerificationSubmission,
    val evidence: List<VerificationEvidence>,
)

data class VerificationDetailsResult(
    val verification: MarketplaceVerification,
    val submissions: List<SubmissionWithEvidence>,
    val decisions: List<VerificationDecision>,
)

data class EvidenceDownload(
    val originalFilename: String,
    val contentType: String,
    val content: ByteArray,
)

@Service
class VerificationTrustService(
    private val verificationStore: MarketplaceVerificationStore,
    private val submissionStore: VerificationSubmissionStore,
    private val evidenceStore: VerificationEvidenceStore,
    private val decisionStore: VerificationDecisionStore,
    private val protectedObjectStore: ProtectedObjectStore,
    private val protectedObjectStorage: ProtectedObjectStorage,
    private val uploadPolicy: ProtectedUploadPolicy,
    private val identityAccountQuery: IdentityAccountQuery,
    private val clock: Clock,
) : VerificationRegistrationPort {
    @Transactional
    override fun initializePending(
        accountId: UUID,
        subjectType: MarketplaceSubjectType,
        now: Instant,
    ): MarketplaceVerification = verificationStore.save(
        MarketplaceVerification(
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
        ),
    )

    @Transactional(readOnly = true)
    override fun findByAccountId(accountId: UUID): List<MarketplaceVerification> = verificationStore.findByAccountId(accountId)

    @Transactional(readOnly = true)
    fun getForCurrent(accountId: UUID, subjectType: MarketplaceSubjectType): VerificationDetailsResult {
        requireSubjectRole(accountId, subjectType)
        val verification = verificationStore.findByAccountIdAndSubjectType(accountId, subjectType)
            ?: throw VerificationNotFoundException()
        return details(verification)
    }

    @Transactional
    fun submit(accountId: UUID, subjectType: MarketplaceSubjectType, uploads: List<EvidenceUpload>): VerificationDetailsResult {
        requireSubjectRole(accountId, subjectType)
        validateUploads(uploads)
        val existing = verificationStore.findByAccountIdAndSubjectType(accountId, subjectType)
            ?: throw VerificationNotFoundException()
        val verification = verificationStore.findByIdForUpdate(existing.id) ?: throw VerificationNotFoundException()
        if (verification.status == MarketplaceVerificationStatus.APPROVED) {
            throw InvalidVerificationTransitionException("An approved verification cannot be resubmitted")
        }
        if (verification.status == MarketplaceVerificationStatus.PENDING && verification.submittedAt != null) {
            throw VerificationAlreadyPendingException()
        }

        val now = clock.instant()
        val submission = submissionStore.save(VerificationSubmission(UUID.randomUUID(), verification.id, now))
        val storedKeys = mutableListOf<String>()
        registerRollbackCleanup(storedKeys)
        val evidence = uploads.map { upload ->
            val objectId = UUID.randomUUID()
            val storageKey = "verification/${verification.id}/$objectId"
            protectedObjectStorage.put(storageKey, upload.content)
            storedKeys += storageKey
            protectedObjectStore.save(ProtectedObject(objectId, storageKey, accountId, now))
            VerificationEvidence(
                id = UUID.randomUUID(),
                submissionId = submission.id,
                identifier = upload.identifier.trim(),
                type = upload.type.trim(),
                originalFilename = safeFilename(upload.originalFilename),
                contentType = upload.contentType.lowercase(),
                sizeBytes = upload.content.size.toLong(),
                protectedObjectId = objectId,
                uploadedAt = now,
            )
        }
        evidenceStore.saveAll(evidence)
        val updated = verificationStore.save(
            verification.copy(
                status = MarketplaceVerificationStatus.PENDING,
                submittedAt = now,
                reviewedAt = null,
                reviewerAdminId = null,
                decisionNote = null,
                updatedAt = now,
            ),
        )
        return details(updated)
    }

    @Transactional(readOnly = true)
    fun listForAdmin(
        adminId: UUID,
        subjectType: MarketplaceSubjectType?,
        status: MarketplaceVerificationStatus?,
        page: Int,
        size: Int,
    ): VerificationPage {
        requireAdmin(adminId)
        if (page < 0 || size !in 1..100) throw InvalidVerificationEvidenceException("page must be non-negative and size must be between 1 and 100")
        return verificationStore.findAll(subjectType, status, page, size)
    }

    @Transactional(readOnly = true)
    fun getForAdmin(adminId: UUID, verificationId: UUID): VerificationDetailsResult {
        requireAdmin(adminId)
        return details(verificationStore.findById(verificationId) ?: throw VerificationNotFoundException())
    }

    @Transactional
    fun decide(
        adminId: UUID,
        verificationId: UUID,
        decision: VerificationDecisionValue,
        reason: String,
    ): VerificationDetailsResult {
        requireAdmin(adminId)
        val note = reason.trim()
        if (note.length !in 3..2000) throw InvalidVerificationTransitionException("A decision reason between 3 and 2000 characters is required")
        val verification = verificationStore.findByIdForUpdate(verificationId) ?: throw VerificationNotFoundException()
        if (verification.status != MarketplaceVerificationStatus.PENDING || verification.submittedAt == null) {
            throw InvalidVerificationTransitionException("Only a submitted pending verification can be decided")
        }
        val now = clock.instant()
        val resultingStatus = MarketplaceVerificationStatus.valueOf(decision.name)
        val updated = verificationStore.save(
            verification.copy(
                status = resultingStatus,
                reviewedAt = now,
                reviewerAdminId = adminId,
                decisionNote = note,
                updatedAt = now,
            ),
        )
        decisionStore.save(
            VerificationDecision(UUID.randomUUID(), verification.id, resultingStatus, adminId, note, now),
        )
        return details(updated)
    }

    @Transactional(readOnly = true)
    fun downloadEvidence(requesterId: UUID, evidenceId: UUID): EvidenceDownload {
        val evidence = evidenceStore.findById(evidenceId) ?: throw VerificationNotFoundException()
        val submission = submissionStore.findById(evidence.submissionId) ?: throw VerificationNotFoundException()
        val verification = verificationStore.findById(submission.verificationId) ?: throw VerificationNotFoundException()
        val isAdmin = identityAccountQuery.hasRole(requesterId, AccountRole.ADMIN)
        if (verification.accountId != requesterId && !isAdmin) throw ForbiddenVerificationAccessException()
        val protectedObject = protectedObjectStore.findById(evidence.protectedObjectId) ?: throw VerificationNotFoundException()
        return EvidenceDownload(
            originalFilename = evidence.originalFilename,
            contentType = evidence.contentType,
            content = protectedObjectStorage.get(protectedObject.storageKey),
        )
    }

    private fun details(verification: MarketplaceVerification): VerificationDetailsResult {
        val submissions = submissionStore.findByVerificationId(verification.id).map {
            SubmissionWithEvidence(it, evidenceStore.findBySubmissionId(it.id))
        }
        return VerificationDetailsResult(verification, submissions, decisionStore.findByVerificationId(verification.id))
    }

    private fun validateUploads(uploads: List<EvidenceUpload>) {
        if (uploads.isEmpty() || uploads.size > 10) throw InvalidVerificationEvidenceException("Between 1 and 10 evidence files are required")
        val identifiers = uploads.map { it.identifier.trim() }
        if (identifiers.toSet().size != identifiers.size) throw InvalidVerificationEvidenceException("Evidence identifiers must be unique")
        uploads.forEach {
            if (!it.identifier.trim().matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                throw InvalidVerificationEvidenceException("Evidence identifiers may contain letters, numbers, underscores, and hyphens")
            }
            if (!it.type.trim().matches(Regex("[A-Za-z0-9 _-]{2,100}"))) {
                throw InvalidVerificationEvidenceException("Evidence type is invalid")
            }
            if (it.content.isEmpty()) throw InvalidVerificationEvidenceException("Evidence files cannot be empty")
            if (it.content.size.toLong() > uploadPolicy.maxFileSizeBytes) throw UploadTooLargeException(uploadPolicy.maxFileSizeBytes)
            if (it.contentType.lowercase() !in uploadPolicy.allowedContentTypes.map(String::lowercase)) {
                throw UnsupportedUploadTypeException(it.contentType)
            }
            safeFilename(it.originalFilename)
        }
    }

    private fun safeFilename(filename: String): String {
        val safe = runCatching { Path.of(filename).fileName.toString() }.getOrDefault("").trim()
        if (safe.isEmpty() || safe.length > 255 || safe.contains('\u0000')) {
            throw InvalidVerificationEvidenceException("Evidence filename is invalid")
        }
        return safe
    }

    private fun requireSubjectRole(accountId: UUID, subjectType: MarketplaceSubjectType) {
        val role = when (subjectType) {
            MarketplaceSubjectType.CUSTOMER -> AccountRole.CUSTOMER
            MarketplaceSubjectType.PROVIDER -> AccountRole.SERVICE_PROVIDER
        }
        if (!identityAccountQuery.hasRole(accountId, role)) throw ProfileRoleRequiredException()
    }

    private fun requireAdmin(accountId: UUID) {
        if (!identityAccountQuery.hasRole(accountId, AccountRole.ADMIN)) throw ForbiddenVerificationAccessException()
    }

    private fun registerRollbackCleanup(storageKeys: List<String>) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storageKeys.forEach { runCatching { protectedObjectStorage.delete(it) } }
                }
            }
        })
    }
}
