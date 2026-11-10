package com.skilledservices.platform.requests.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PostingFeeStatus { PENDING, FAILED, SUCCESSFUL }
enum class PaymentNextActionType { REDIRECT, DISPLAY_INSTRUCTIONS, NONE }
enum class VerifiedPaymentOutcome { SUCCESS, FAILED }

data class PostingFeeTransaction(
    val id: UUID,
    val requestId: UUID,
    val customerId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: PostingFeeStatus,
    val providerIdentifier: String,
    val providerReferenceId: String?,
    val providerEventId: String?,
    val idempotencyKey: UUID,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val verifiedAt: Instant?,
)

data class PaymentNextAction(val type: PaymentNextActionType, val url: String?, val instructions: String?)
data class PaymentInitiationResult(val providerReferenceId: String, val nextAction: PaymentNextAction)
data class VerifiedPaymentEvent(
    val paymentId: UUID,
    val providerIdentifier: String,
    val providerReferenceId: String,
    val providerEventId: String,
    val outcome: VerifiedPaymentOutcome,
    val amount: BigDecimal,
    val currency: String,
    val failureCode: String?,
)

interface PostingFeePaymentGateway {
    val providerIdentifier: String
    val available: Boolean
    fun initiate(paymentId: UUID, requestId: UUID, amount: BigDecimal, currency: String, idempotencyKey: UUID): PaymentInitiationResult
    fun verifyCallback(provider: String, body: ByteArray, headers: Map<String, String>): VerifiedPaymentEvent
}

interface PostingFeePolicy {
    val amount: BigDecimal
    val currency: String
}

interface PostingFeeTransactionStore {
    fun save(transaction: PostingFeeTransaction): PostingFeeTransaction
    fun findById(id: UUID): PostingFeeTransaction?
    fun findByIdForUpdate(id: UUID): PostingFeeTransaction?
    fun findByRequestId(requestId: UUID): List<PostingFeeTransaction>
    fun findSuccessfulByRequestId(requestId: UUID): PostingFeeTransaction?
    fun findByProviderEvent(provider: String, eventId: String): PostingFeeTransaction?
}

enum class ProposalStatus { PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED }

data class Proposal(
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

interface ProposalStore {
    fun save(proposal: Proposal): Proposal
    fun findById(id: UUID): Proposal?
    fun findByIdForUpdate(id: UUID): Proposal?
    fun findByRequestAndProvider(requestId: UUID, providerId: UUID): Proposal?
    fun findByRequestId(requestId: UUID): List<Proposal>
}

data class MarketplaceActivityEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val actorId: UUID?,
    val occurredAt: Instant,
)

interface MarketplaceActivityStore { fun save(event: MarketplaceActivityEvent) }
