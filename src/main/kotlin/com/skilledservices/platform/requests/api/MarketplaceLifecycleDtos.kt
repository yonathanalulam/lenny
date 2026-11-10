package com.skilledservices.platform.requests.api

import com.skilledservices.platform.requests.application.*
import com.skilledservices.platform.requests.domain.*
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class PaymentNextActionResponse(val type: PaymentNextActionType, val url: String?, val instructions: String?)
data class PostingFeePaymentResponse(
    val paymentId: UUID,
    val requestId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: PostingFeeStatus,
    val provider: String,
    val nextAction: PaymentNextActionResponse?,
    val published: Boolean,
    val createdAt: Instant,
    val verifiedAt: Instant?,
)
data class PostingFeeStatusResponse(
    val requestId: UUID,
    val paymentRequired: Boolean,
    val amount: BigDecimal,
    val currency: String,
    val transactionStatus: PostingFeeStatus?,
    val paymentId: UUID?,
    val published: Boolean,
)
data class PaymentWebhookResponse(val paymentId: UUID, val status: PostingFeeStatus, val published: Boolean)

data class ProviderJobResponse(
    val requestId: UUID,
    val categoryId: UUID,
    val description: String,
    val generalArea: String,
    val preferredDate: LocalDate,
    val preferredStartTime: LocalTime?,
    val preferredEndTime: LocalTime?,
    val urgency: RequestUrgency?,
    val budgetMin: BigDecimal?,
    val budgetMax: BigDecimal?,
    val specialInstructions: String?,
    val attachments: List<RequestAttachmentResponse>,
    val publishedAt: Instant,
)

data class InvitationStateResponse(val invitationId: UUID, val requestId: UUID, val status: InvitationStatus, val updatedAt: Instant)

data class SubmitProposalRequest(
    @field:DecimalMin("0.01") @field:Digits(integer = 10, fraction = 2) val proposedPrice: BigDecimal,
    @field:NotBlank @field:Size(min = 2, max = 500) val expectedTimeline: String,
    @field:NotBlank @field:Size(min = 10, max = 4000) val providerMessage: String,
    @field:Size(max = 2000) val conditions: String? = null,
)

data class ProposalResponse(
    val id: UUID,
    val requestId: UUID,
    val providerId: UUID,
    val proposedPrice: BigDecimal,
    val expectedTimeline: String,
    val providerMessage: String,
    val conditions: String?,
    val status: ProposalStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val withdrawnAt: Instant?,
    val rejectedAt: Instant?,
)

data class ProposalProviderSummary(
    val providerId: UUID,
    val professionalDescription: String,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val profilePhotoAvailable: Boolean,
)
data class CustomerProposalResponse(val proposal: ProposalResponse, val provider: ProposalProviderSummary)

internal fun PostingFeePaymentResult.toResponse() = PostingFeePaymentResponse(
    transaction.id, transaction.requestId, transaction.amount, transaction.currency, transaction.status,
    transaction.providerIdentifier, nextAction?.let { PaymentNextActionResponse(it.type, it.url, it.instructions) },
    published, transaction.createdAt, transaction.verifiedAt,
)
internal fun PostingFeeStatusResult.toResponse() = PostingFeeStatusResponse(
    requestId, paymentRequired, amount, currency, latestTransaction?.status, latestTransaction?.id, published,
)
internal fun ProviderJobDetails.toResponse() = ProviderJobResponse(
    request.id, request.categoryId, request.description, request.generalArea, request.preferredDate,
    request.preferredStartTime, request.preferredEndTime, request.urgency, request.budgetMin, request.budgetMax,
    request.specialInstructions, attachments.map { it.toResponse() }, request.updatedAt,
)
internal fun DirectInvitation.toStateResponse() = InvitationStateResponse(id, requestId, status, updatedAt)
internal fun SubmitProposalRequest.toCommand() = SubmitProposalCommand(proposedPrice, expectedTimeline, providerMessage, conditions)
internal fun Proposal.toResponse() = ProposalResponse(id, requestId, providerId, proposedPrice, expectedTimeline, providerMessage,
    conditions, status, createdAt, updatedAt, withdrawnAt, rejectedAt)
internal fun CustomerProposalDetails.toResponse() = CustomerProposalResponse(
    proposal.toResponse(), ProposalProviderSummary(provider.providerId, provider.professionalDescription, provider.skills,
        provider.experienceYears, provider.serviceArea, provider.pricingSummary, provider.profilePhotoAvailable),
)
