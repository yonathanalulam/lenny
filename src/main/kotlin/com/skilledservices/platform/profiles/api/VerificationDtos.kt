package com.skilledservices.platform.profiles.api

import com.skilledservices.platform.profiles.application.EvidenceUpload
import com.skilledservices.platform.profiles.application.VerificationDetailsResult
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import com.skilledservices.platform.profiles.domain.VerificationDecisionValue
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

data class EvidenceDescriptorRequest(
    @field:NotBlank @field:Size(max = 100)
    @field:Schema(example = "identity-front")
    val identifier: String,
    @field:NotBlank @field:Size(min = 2, max = 100)
    @field:Schema(example = "Identity evidence")
    val type: String,
)

data class VerificationSubmissionRequest(
    @field:Size(min = 1, max = 10)
    val evidence: List<@Valid EvidenceDescriptorRequest>,
)

data class VerificationDecisionRequest(
    val decision: VerificationDecisionValue,
    @field:NotBlank @field:Size(min = 3, max = 2000)
    val reason: String,
)

data class VerificationEvidenceResponse(
    val id: UUID,
    val identifier: String,
    val type: String,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val downloadPath: String,
)

data class VerificationSubmissionResponse(
    val id: UUID,
    val submittedAt: Instant,
    val evidence: List<VerificationEvidenceResponse>,
)

data class VerificationDecisionResponse(
    val resultingStatus: String,
    val reason: String,
    val decidedAt: Instant,
    val reviewerAdminId: UUID?,
)

data class VerificationResponse(
    val id: UUID,
    val subjectUserId: UUID,
    val subjectType: String,
    val status: String,
    val submittedAt: Instant?,
    val reviewedAt: Instant?,
    val decisionReason: String?,
    val reviewerAdminId: UUID?,
    val submissions: List<VerificationSubmissionResponse>,
    val decisions: List<VerificationDecisionResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VerificationSummaryResponse(
    val id: UUID,
    val subjectUserId: UUID,
    val subjectType: String,
    val status: String,
    val submittedAt: Instant?,
    val updatedAt: Instant,
)

internal fun verificationUploads(
    metadata: VerificationSubmissionRequest,
    files: List<MultipartFile>,
): List<EvidenceUpload> {
    if (metadata.evidence.size != files.size) {
        throw com.skilledservices.platform.profiles.domain.InvalidVerificationEvidenceException(
            "Each evidence descriptor must have one file in the same list position",
        )
    }
    return metadata.evidence.zip(files).map { (descriptor, file) ->
        EvidenceUpload(
            identifier = descriptor.identifier,
            type = descriptor.type,
            originalFilename = file.originalFilename ?: "evidence",
            contentType = file.contentType ?: "application/octet-stream",
            content = file.bytes,
        )
    }
}

internal fun VerificationDetailsResult.toResponse(adminView: Boolean) = VerificationResponse(
    id = verification.id,
    subjectUserId = verification.accountId,
    subjectType = verification.subjectType.name,
    status = verification.status.name,
    submittedAt = verification.submittedAt,
    reviewedAt = verification.reviewedAt,
    decisionReason = verification.decisionNote,
    reviewerAdminId = verification.reviewerAdminId.takeIf { adminView },
    submissions = submissions.map { item ->
        VerificationSubmissionResponse(
            id = item.submission.id,
            submittedAt = item.submission.submittedAt,
            evidence = item.evidence.map {
                VerificationEvidenceResponse(
                    it.id, it.identifier, it.type, it.originalFilename, it.contentType, it.sizeBytes, it.uploadedAt,
                    "/api/v1/verifications/evidence/${it.id}/content",
                )
            },
        )
    },
    decisions = decisions.map {
        VerificationDecisionResponse(it.resultingStatus.name, it.reason, it.decidedAt, it.reviewerAdminId.takeIf { adminView })
    },
    createdAt = verification.createdAt,
    updatedAt = verification.updatedAt,
)

internal fun MarketplaceVerification.toSummary() = VerificationSummaryResponse(
    id, accountId, subjectType.name, status.name, submittedAt, updatedAt,
)

internal fun com.skilledservices.platform.profiles.domain.VerificationPage.toResponse() = PageResponse(
    items = items.map { it.toSummary() },
    page = page,
    size = size,
    totalItems = totalItems,
    totalPages = totalPages,
    hasNext = page + 1 < totalPages,
)
