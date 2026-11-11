package com.skilledservices.platform.booking.api

import com.skilledservices.platform.booking.application.*
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class ConfirmBookingRequest(val scheduledStart: OffsetDateTime, val scheduledEnd: OffsetDateTime)
data class CancelBookingRequest(@field:Size(max = 500) val reason: String? = null)
data class BookingStatusHistoryResponse(val fromStatus: String?, val toStatus: String, val actorContext: String, val occurredAt: Instant, val reason: String?)
data class CustomerBookingResponse(val id: UUID, val requestId: UUID, val proposalId: UUID, val providerId: UUID,
    val scheduledStart: Instant, val scheduledEnd: Instant, val agreedPrice: BigDecimal, val currency: String,
    val status: String, val generalArea: String, val exactLocation: String, val description: String, val confirmedAt: Instant,
    val startedAt: Instant?, val providerReportedCompleteAt: Instant?, val completedAt: Instant?, val cancelledAt: Instant?,
    val cancellationReason: String?, val statusHistory: List<BookingStatusHistoryResponse>)
data class ProviderBookingResponse(val id: UUID, val requestId: UUID, val proposalId: UUID, val customerId: UUID,
    val scheduledStart: Instant, val scheduledEnd: Instant, val agreedPrice: BigDecimal, val currency: String,
    val status: String, val generalArea: String, val exactLocation: String, val customerPhone: String?, val description: String, val confirmedAt: Instant,
    val startedAt: Instant?, val providerReportedCompleteAt: Instant?, val completedAt: Instant?, val cancelledAt: Instant?,
    val cancellationReason: String?, val statusHistory: List<BookingStatusHistoryResponse>)

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
class CustomerBookingController(private val service: BookingService) {
    @PostMapping("/proposals/{proposalId}/accept")
    @Operation(summary = "Atomically accept a proposal and confirm one Booking")
    fun accept(@AuthenticationPrincipal jwt: Jwt, @PathVariable proposalId: UUID, @Valid @RequestBody request: ConfirmBookingRequest) =
        service.confirm(jwt.accountId(), proposalId, ConfirmBookingCommand(request.scheduledStart, request.scheduledEnd)).toCustomerResponse()
    @GetMapping("/bookings") fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: com.skilledservices.platform.booking.domain.BookingStatus?,
        @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) =
        service.listCustomer(jwt.accountId(), status, page, size).map { it.toCustomerResponse() }
    @GetMapping("/bookings/{bookingId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.getCustomer(jwt.accountId(), bookingId).toCustomerResponse()
    @PostMapping("/bookings/{bookingId}/confirm-completion")
    @Operation(summary = "Confirm provider-reported completion")
    fun confirmCompletion(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.confirmCompletion(jwt.accountId(), bookingId).toCustomerResponse()
    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel a confirmed Booking before service starts")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID, @Valid @RequestBody request: CancelBookingRequest) =
        service.cancelCustomer(jwt.accountId(), bookingId, CancelBookingCommand(request.reason)).toCustomerResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/bookings")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
class ProviderBookingController(private val service: BookingService) {
    @GetMapping fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(required = false) status: com.skilledservices.platform.booking.domain.BookingStatus?,
        @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) =
        service.listProvider(jwt.accountId(), status, page, size).map { it.toProviderResponse() }
    @GetMapping("/{bookingId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.getProvider(jwt.accountId(), bookingId).toProviderResponse()
    @PostMapping("/{bookingId}/start")
    @Operation(summary = "Start service for a confirmed Booking")
    fun start(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.startProvider(jwt.accountId(), bookingId).toProviderResponse()
    @PostMapping("/{bookingId}/report-complete")
    @Operation(summary = "Report service work complete for customer confirmation")
    fun reportComplete(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.reportComplete(jwt.accountId(), bookingId).toProviderResponse()
}

private fun BookingDetails.toCustomerResponse() = CustomerBookingResponse(booking.id, booking.requestId, booking.proposalId, booking.providerId,
    booking.scheduledStart, booking.scheduledEnd, booking.agreedPrice, booking.currency, booking.status.name,
    request.generalArea, request.exactLocation, request.description, booking.confirmedAt, booking.startedAt,
    booking.providerReportedCompleteAt, booking.completedAt, booking.cancelledAt, booking.cancellationReason, history.map { it.toResponse() })
private fun BookingDetails.toProviderResponse() = ProviderBookingResponse(booking.id, booking.requestId, booking.proposalId, booking.customerId,
    booking.scheduledStart, booking.scheduledEnd, booking.agreedPrice, booking.currency, booking.status.name,
    request.generalArea, request.exactLocation, customerPhone, request.description, booking.confirmedAt, booking.startedAt,
    booking.providerReportedCompleteAt, booking.completedAt, booking.cancelledAt, booking.cancellationReason, history.map { it.toResponse() })
private fun com.skilledservices.platform.booking.domain.BookingStatusHistory.toResponse() = BookingStatusHistoryResponse(
    fromStatus?.name, toStatus.name, actorContext.name, occurredAt, reason)
private fun Jwt.accountId() = UUID.fromString(subject)
private fun <T, R> PageResponse<T>.map(transform: (T) -> R) = PageResponse(items.map(transform), page, size, totalItems, totalPages, hasNext)
