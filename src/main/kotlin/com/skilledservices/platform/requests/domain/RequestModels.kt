package com.skilledservices.platform.requests.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class ServiceRequestType { DIRECT_INVITATION, JOB_POSTING }
enum class ServiceRequestStatus { DRAFT, AWAITING_POSTING_FEE, OPEN, PROVIDER_SELECTED, CANCELLED }
enum class RequestUrgency { FLEXIBLE, NORMAL, URGENT }
enum class InvitationStatus { PENDING, ACCEPTED, DECLINED, WITHDRAWN, EXPIRED }

data class ServiceRequest(
    val id: UUID,
    val customerId: UUID,
    val type: ServiceRequestType,
    val status: ServiceRequestStatus,
    val categoryId: UUID,
    val providerId: UUID?,
    val description: String,
    val generalArea: String,
    val exactLocation: String,
    val preferredDate: LocalDate,
    val preferredStartTime: LocalTime?,
    val preferredEndTime: LocalTime?,
    val urgency: RequestUrgency?,
    val budgetMin: BigDecimal?,
    val budgetMax: BigDecimal?,
    val specialInstructions: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val cancelledAt: Instant?,
)

data class DirectInvitation(
    val id: UUID,
    val requestId: UUID,
    val providerId: UUID,
    val status: InvitationStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RequestAttachment(
    val id: UUID,
    val requestId: UUID,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val protectedObjectId: UUID,
    val uploadedAt: Instant,
)

interface ServiceRequestStore {
    fun save(request: ServiceRequest): ServiceRequest
    fun findById(id: UUID): ServiceRequest?
    fun findByIdForUpdate(id: UUID): ServiceRequest?
    fun findByCustomerId(customerId: UUID): List<ServiceRequest>
    fun findOpenJobPostings(): List<ServiceRequest>
}

interface InvitationStore {
    fun save(invitation: DirectInvitation): DirectInvitation
    fun findById(id: UUID): DirectInvitation?
    fun findByIdForUpdate(id: UUID): DirectInvitation?
    fun findByRequestId(requestId: UUID): DirectInvitation?
    fun findByProviderId(providerId: UUID): List<DirectInvitation>
}

interface RequestAttachmentStore {
    fun save(attachment: RequestAttachment): RequestAttachment
    fun findById(id: UUID): RequestAttachment?
    fun findByRequestId(requestId: UUID): List<RequestAttachment>
}

open class RequestsException(val code: String, message: String) : RuntimeException(message)
class CustomerNotEligibleException : RequestsException("CUSTOMER_NOT_MARKETPLACE_ELIGIBLE", "Customer is not eligible to create marketplace requests")
class ProviderNotEligibleException : RequestsException("PROVIDER_NOT_MARKETPLACE_ELIGIBLE", "Provider is not marketplace eligible")
class RequestNotFoundException : RequestsException("REQUEST_NOT_FOUND", "Service request was not found")
class RequestNotOwnedException : RequestsException("REQUEST_NOT_OWNED", "Service request belongs to another customer")
class InvalidRequestTypeException(message: String) : RequestsException("INVALID_REQUEST_TYPE", message)
class InvalidRequestStateException(message: String) : RequestsException("INVALID_REQUEST_STATE", message)
class InvalidBudgetRangeException : RequestsException("INVALID_BUDGET_RANGE", "Budget values must be non-negative and minimum must not exceed maximum")
class InvalidServiceDateException : RequestsException("INVALID_SERVICE_DATE", "Preferred service date cannot be in the past")
class InvalidRequestException(message: String) : RequestsException("REQUEST_INVALID", message)
class InvitationNotFoundException : RequestsException("INVITATION_NOT_FOUND", "Invitation was not found")
class AttachmentAccessDeniedException : RequestsException("FORBIDDEN_REQUEST_ATTACHMENT_ACCESS", "Request attachment is not accessible")
class UnsupportedRequestAttachmentTypeException(type: String) : RequestsException("UNSUPPORTED_UPLOAD_TYPE", "Unsupported request attachment type: $type")
class RequestAttachmentTooLargeException(limit: Long) : RequestsException("UPLOAD_TOO_LARGE", "Request attachment exceeds the $limit byte limit")
class PostingFeeNotRequiredException : RequestsException("POSTING_FEE_NOT_REQUIRED", "Posting Fee is not required for this request")
class PostingFeeAlreadyPaidException : RequestsException("POSTING_FEE_ALREADY_PAID", "Posting Fee has already been paid")
class PaymentProviderUnavailableException : RequestsException("PAYMENT_PROVIDER_UNAVAILABLE", "No supported Posting Fee payment provider is configured")
class PaymentInitiationFailedException : RequestsException("PAYMENT_INITIATION_FAILED", "Posting Fee payment could not be initiated")
class PaymentVerificationFailedException : RequestsException("PAYMENT_VERIFICATION_FAILED", "Payment result could not be verified")
class PaymentAmountMismatchException : RequestsException("PAYMENT_AMOUNT_MISMATCH", "Verified payment amount or currency does not match the transaction")
class PaymentCallbackInvalidException : RequestsException("PAYMENT_CALLBACK_INVALID", "Payment callback is invalid")
class JobNotOpenException : RequestsException("JOB_NOT_OPEN", "Job Posting is not open")
class JobNotEligibleException : RequestsException("JOB_NOT_ELIGIBLE", "Provider is not eligible for this Job Posting")
class InvitationNotPendingException : RequestsException("INVITATION_NOT_PENDING", "Invitation is not pending")
class InvitationNotOwnedException : RequestsException("INVITATION_NOT_OWNED", "Invitation does not belong to this provider")
class ProposalAlreadyExistsException : RequestsException("PROPOSAL_ALREADY_EXISTS", "A proposal already exists for this provider and request")
class ProposalNotFoundException : RequestsException("PROPOSAL_NOT_FOUND", "Proposal was not found")
class ProposalNotPendingException : RequestsException("PROPOSAL_NOT_PENDING", "Proposal is not pending")
class ProposalNotOwnedException : RequestsException("PROPOSAL_NOT_OWNED", "Proposal is not accessible to this account")
