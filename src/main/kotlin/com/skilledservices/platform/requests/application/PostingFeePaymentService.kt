package com.skilledservices.platform.requests.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.profiles.application.CustomerEligibilityService
import com.skilledservices.platform.requests.domain.*
import com.skilledservices.platform.shared.events.PlatformNotification
import com.skilledservices.platform.shared.events.PlatformNotificationPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

data class PostingFeePaymentResult(
    val transaction: PostingFeeTransaction,
    val nextAction: PaymentNextAction?,
    val published: Boolean,
)

data class PostingFeeStatusResult(
    val requestId: UUID,
    val paymentRequired: Boolean,
    val amount: java.math.BigDecimal,
    val currency: String,
    val latestTransaction: PostingFeeTransaction?,
    val published: Boolean,
)

data class VerifiedPaymentApplicationResult(
    val payment: PostingFeeTransaction,
    val request: ServiceRequest,
    val problem: RequestsException? = null,
)

@Service
class PostingFeePaymentService(
    private val coordinator: PostingFeeTransactionCoordinator,
    private val gateway: PostingFeePaymentGateway,
) {
    fun initiate(customerId: UUID, requestId: UUID): PostingFeePaymentResult {
        if (!gateway.available) throw PaymentProviderUnavailableException()
        val pending = coordinator.preparePending(customerId, requestId, gateway.providerIdentifier)
        val initiation = try {
            gateway.initiate(pending.id, pending.requestId, pending.amount, pending.currency, pending.idempotencyKey)
        } catch (exception: RequestsException) {
            coordinator.markInitiationFailed(pending.id, "PROVIDER_UNAVAILABLE")
            throw exception
        } catch (_: Exception) {
            coordinator.markInitiationFailed(pending.id, "INITIATION_FAILED")
            throw PaymentInitiationFailedException()
        }
        val updated = coordinator.recordProviderReference(pending.id, initiation.providerReferenceId)
        return PostingFeePaymentResult(updated, initiation.nextAction, published = false)
    }

    fun status(customerId: UUID, requestId: UUID) = coordinator.status(customerId, requestId)

    fun processCallback(provider: String, body: ByteArray, headers: Map<String, String>): VerifiedPaymentApplicationResult {
        val verified = gateway.verifyCallback(provider, body, headers)
        val result = coordinator.applyVerifiedEvent(verified)
        result.problem?.let { throw it }
        return result
    }
}

@Service
class PostingFeeTransactionCoordinator(
    private val requests: ServiceRequestStore,
    private val payments: PostingFeeTransactionStore,
    private val activities: MarketplaceActivityStore,
    private val customerEligibility: CustomerEligibilityService,
    private val identity: IdentityAccountQuery,
    private val properties: PostingFeePolicy,
    private val notifications: PlatformNotificationPort,
    private val clock: Clock,
) {
    private val amount = properties.amount.setScale(2, RoundingMode.UNNECESSARY).also { require(it.signum() > 0) { "Posting Fee amount must be positive" } }
    private val currency = properties.currency.uppercase().also { require(it.matches(Regex("[A-Z]{3}"))) { "Posting Fee currency must be a three-letter code" } }

    @Transactional
    fun preparePending(customerId: UUID, requestId: UUID, provider: String): PostingFeeTransaction {
        requireCustomer(customerId)
        if (!customerEligibility.canCreateMarketplaceRequest(customerId)) throw CustomerNotEligibleException()
        val request = requests.findByIdForUpdate(requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw RequestNotOwnedException()
        if (request.type != ServiceRequestType.JOB_POSTING) throw PostingFeeNotRequiredException()
        if (request.status == ServiceRequestStatus.OPEN || payments.findSuccessfulByRequestId(requestId) != null) throw PostingFeeAlreadyPaidException()
        if (request.status != ServiceRequestStatus.AWAITING_POSTING_FEE) throw InvalidRequestStateException("Job Posting must be awaiting its Posting Fee")
        payments.findByRequestId(requestId).firstOrNull { it.status == PostingFeeStatus.PENDING }?.let { return it }
        val now = clock.instant()
        val payment = payments.save(
            PostingFeeTransaction(UUID.randomUUID(), requestId, customerId, amount, currency, PostingFeeStatus.PENDING,
                provider, null, null, UUID.randomUUID(), null, now, now, null),
        )
        activity("POSTING_FEE", payment.id, "POSTING_FEE_INITIATED", customerId, now)
        return payment
    }

    @Transactional
    fun recordProviderReference(paymentId: UUID, providerReference: String): PostingFeeTransaction {
        val payment = payments.findByIdForUpdate(paymentId) ?: throw PaymentVerificationFailedException()
        if (payment.status != PostingFeeStatus.PENDING) return payment
        return payments.save(payment.copy(providerReferenceId = providerReference, updatedAt = clock.instant()))
    }

    @Transactional
    fun markInitiationFailed(paymentId: UUID, code: String) {
        val payment = payments.findByIdForUpdate(paymentId) ?: return
        if (payment.status != PostingFeeStatus.PENDING) return
        val now = clock.instant()
        payments.save(payment.copy(status = PostingFeeStatus.FAILED, failureCode = code.take(100), updatedAt = now))
        activity("POSTING_FEE", payment.id, "POSTING_FEE_INITIATION_FAILED", payment.customerId, now)
    }

    @Transactional(readOnly = true)
    fun status(customerId: UUID, requestId: UUID): PostingFeeStatusResult {
        requireCustomer(customerId)
        val request = requests.findById(requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw RequestNotOwnedException()
        if (request.type != ServiceRequestType.JOB_POSTING) throw PostingFeeNotRequiredException()
        val latest = payments.findByRequestId(requestId).firstOrNull()
        return PostingFeeStatusResult(requestId, request.status == ServiceRequestStatus.AWAITING_POSTING_FEE, latest?.amount ?: amount,
            latest?.currency ?: currency, latest, request.status == ServiceRequestStatus.OPEN)
    }

    @Transactional
    fun applyVerifiedEvent(event: VerifiedPaymentEvent): VerifiedPaymentApplicationResult {
        payments.findByProviderEvent(event.providerIdentifier, event.providerEventId)?.let { replayed ->
            val request = requests.findById(replayed.requestId) ?: throw RequestNotFoundException()
            return VerifiedPaymentApplicationResult(replayed, request)
        }
        val payment = payments.findByIdForUpdate(event.paymentId) ?: throw PaymentVerificationFailedException()
        if (payment.providerIdentifier != event.providerIdentifier) throw PaymentCallbackInvalidException()
        if (payment.status == PostingFeeStatus.SUCCESSFUL) {
            val request = requests.findById(payment.requestId) ?: throw RequestNotFoundException()
            return VerifiedPaymentApplicationResult(payment, request)
        }
        val request = requests.findByIdForUpdate(payment.requestId) ?: throw RequestNotFoundException()
        val now = clock.instant()
        if (event.providerReferenceId != payment.providerReferenceId && payment.providerReferenceId != null) {
            val failed = fail(payment, event, "REFERENCE_MISMATCH", now)
            return VerifiedPaymentApplicationResult(failed, request, PaymentVerificationFailedException())
        }
        if (event.amount.compareTo(payment.amount) != 0 || event.currency != payment.currency) {
            val failed = fail(payment, event, "AMOUNT_OR_CURRENCY_MISMATCH", now)
            return VerifiedPaymentApplicationResult(failed, request, PaymentAmountMismatchException())
        }
        if (event.outcome == VerifiedPaymentOutcome.FAILED) {
            val failed = fail(payment, event, event.failureCode ?: "PROVIDER_REPORTED_FAILED", now)
            activity("POSTING_FEE", payment.id, "POSTING_FEE_FAILED", payment.customerId, now)
            return VerifiedPaymentApplicationResult(failed, request)
        }
        if (request.type != ServiceRequestType.JOB_POSTING || request.status != ServiceRequestStatus.AWAITING_POSTING_FEE) {
            val already = payments.findSuccessfulByRequestId(request.id)
            if (request.status == ServiceRequestStatus.OPEN && already != null) return VerifiedPaymentApplicationResult(already, request)
            return VerifiedPaymentApplicationResult(payment, request, PostingFeeAlreadyPaidException())
        }
        if (payments.findSuccessfulByRequestId(request.id) != null) return VerifiedPaymentApplicationResult(payment, request, PostingFeeAlreadyPaidException())
        val successful = payments.save(payment.copy(status = PostingFeeStatus.SUCCESSFUL, providerReferenceId = event.providerReferenceId,
            providerEventId = event.providerEventId, failureCode = null, updatedAt = now, verifiedAt = now))
        val published = requests.save(request.copy(status = ServiceRequestStatus.OPEN, updatedAt = now))
        activity("POSTING_FEE", payment.id, "POSTING_FEE_SUCCEEDED", payment.customerId, now)
        activity("SERVICE_REQUEST", request.id, "JOB_POSTING_PUBLISHED", payment.customerId, now)
        notifications.publish(PlatformNotification(payment.customerId, "JOB_POSTING_PUBLISHED", "Job Posting published",
            "Your Posting Fee was verified and the Job Posting is now open.", "SERVICE_REQUEST", request.id))
        return VerifiedPaymentApplicationResult(successful, published)
    }

    private fun fail(payment: PostingFeeTransaction, event: VerifiedPaymentEvent, code: String, now: java.time.Instant) =
        payments.save(payment.copy(status = PostingFeeStatus.FAILED, providerReferenceId = event.providerReferenceId,
            providerEventId = event.providerEventId, failureCode = code.take(100), updatedAt = now, verifiedAt = null))

    private fun requireCustomer(id: UUID) { if (!identity.hasRole(id, AccountRole.CUSTOMER)) throw RequestNotOwnedException() }
    private fun activity(type: String, id: UUID, event: String, actor: UUID?, at: java.time.Instant) {
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), type, id, event, actor, at))
    }
}
