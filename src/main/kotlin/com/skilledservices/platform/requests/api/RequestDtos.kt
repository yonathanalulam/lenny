package com.skilledservices.platform.requests.api

import com.skilledservices.platform.requests.application.*
import com.skilledservices.platform.requests.domain.*
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.FutureOrPresent
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class CreateServiceRequestRequest(
    val type: ServiceRequestType,
    val categoryId: UUID,
    val providerId: UUID? = null,
    @field:NotBlank @field:Size(min = 10, max = 4000) val description: String,
    @field:NotBlank @field:Size(min = 2, max = 160) val generalArea: String,
    @field:NotBlank @field:Size(min = 5, max = 1000) val exactLocation: String,
    @field:FutureOrPresent val preferredDate: LocalDate,
    @field:Schema(type = "string", format = "time") val preferredStartTime: LocalTime? = null,
    @field:Schema(type = "string", format = "time") val preferredEndTime: LocalTime? = null,
    val urgency: RequestUrgency? = null,
    @field:DecimalMin("0.00") @field:Digits(integer = 10, fraction = 2) val budgetMin: BigDecimal? = null,
    @field:DecimalMin("0.00") @field:Digits(integer = 10, fraction = 2) val budgetMax: BigDecimal? = null,
    @field:Size(max = 4000) val specialInstructions: String? = null,
)

data class UpdateServiceRequestRequest(
    @field:Size(min = 10, max = 4000) val description: String? = null,
    @field:Size(min = 2, max = 160) val generalArea: String? = null,
    @field:Size(min = 5, max = 1000) val exactLocation: String? = null,
    @field:FutureOrPresent val preferredDate: LocalDate? = null,
    val preferredStartTime: LocalTime? = null,
    val preferredEndTime: LocalTime? = null,
    val urgency: RequestUrgency? = null,
    @field:DecimalMin("0.00") @field:Digits(integer = 10, fraction = 2) val budgetMin: BigDecimal? = null,
    @field:DecimalMin("0.00") @field:Digits(integer = 10, fraction = 2) val budgetMax: BigDecimal? = null,
    @field:Size(max = 4000) val specialInstructions: String? = null,
)

data class RequestAttachmentResponse(
    val id: UUID,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedAt: Instant,
    val downloadPath: String,
)

data class CustomerServiceRequestResponse(
    val id: UUID,
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
    val invitationStatus: InvitationStatus?,
    val postingFeeRequired: Boolean,
    val published: Boolean,
    val attachments: List<RequestAttachmentResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val cancelledAt: Instant?,
)

data class ProviderInvitationResponse(
    val invitationId: UUID,
    val invitationStatus: InvitationStatus,
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
    val createdAt: Instant,
)

internal fun CreateServiceRequestRequest.toCommand() = CreateRequestCommand(type, categoryId, providerId, description, generalArea, exactLocation, preferredDate, preferredStartTime, preferredEndTime, urgency, budgetMin, budgetMax, specialInstructions)
internal fun UpdateServiceRequestRequest.toCommand() = UpdateRequestCommand(description, generalArea, exactLocation, preferredDate, preferredStartTime, preferredEndTime, urgency, budgetMin, budgetMax, specialInstructions)
internal fun RequestAttachment.toResponse() = RequestAttachmentResponse(id, originalFilename, contentType, sizeBytes, uploadedAt, "/api/v1/requests/attachments/$id/content")
internal fun RequestDetails.toCustomerResponse() = CustomerServiceRequestResponse(
    request.id, request.type, request.status, request.categoryId, request.providerId, request.description, request.generalArea,
    request.exactLocation, request.preferredDate, request.preferredStartTime, request.preferredEndTime, request.urgency,
    request.budgetMin, request.budgetMax, request.specialInstructions, invitation?.status,
    request.type == ServiceRequestType.JOB_POSTING && request.status == ServiceRequestStatus.AWAITING_POSTING_FEE,
    request.status == ServiceRequestStatus.OPEN, attachments.map { it.toResponse() }, request.createdAt, request.updatedAt, request.cancelledAt,
)
internal fun ProviderInvitationDetails.toProviderResponse() = ProviderInvitationResponse(
    invitation.id, invitation.status, request.id, request.categoryId, request.description, request.generalArea,
    request.preferredDate, request.preferredStartTime, request.preferredEndTime, request.urgency,
    request.budgetMin, request.budgetMax, request.specialInstructions, attachments.map { it.toResponse() }, invitation.createdAt,
)
