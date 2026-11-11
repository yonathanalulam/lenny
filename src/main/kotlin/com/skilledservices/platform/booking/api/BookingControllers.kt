package com.skilledservices.platform.booking.api

import com.skilledservices.platform.booking.application.*
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class ConfirmBookingRequest(val scheduledStart: OffsetDateTime, val scheduledEnd: OffsetDateTime)
data class CustomerBookingResponse(val id: UUID, val requestId: UUID, val proposalId: UUID, val providerId: UUID,
    val scheduledStart: Instant, val scheduledEnd: Instant, val agreedPrice: BigDecimal, val currency: String,
    val status: String, val generalArea: String, val exactLocation: String, val description: String, val confirmedAt: Instant)
data class ProviderBookingResponse(val id: UUID, val requestId: UUID, val proposalId: UUID, val customerId: UUID,
    val scheduledStart: Instant, val scheduledEnd: Instant, val agreedPrice: BigDecimal, val currency: String,
    val status: String, val generalArea: String, val exactLocation: String, val customerPhone: String?, val description: String, val confirmedAt: Instant)

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
class CustomerBookingController(private val service: BookingService) {
    @PostMapping("/proposals/{proposalId}/accept")
    @Operation(summary = "Atomically accept a proposal and confirm one Booking")
    fun accept(@AuthenticationPrincipal jwt: Jwt, @PathVariable proposalId: UUID, @Valid @RequestBody request: ConfirmBookingRequest) =
        service.confirm(jwt.accountId(), proposalId, ConfirmBookingCommand(request.scheduledStart, request.scheduledEnd)).toCustomerResponse()
    @GetMapping("/bookings") fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) =
        service.listCustomer(jwt.accountId(), page, size).map { it.toCustomerResponse() }
    @GetMapping("/bookings/{bookingId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.getCustomer(jwt.accountId(), bookingId).toCustomerResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/bookings")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
class ProviderBookingController(private val service: BookingService) {
    @GetMapping fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) =
        service.listProvider(jwt.accountId(), page, size).map { it.toProviderResponse() }
    @GetMapping("/{bookingId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID) = service.getProvider(jwt.accountId(), bookingId).toProviderResponse()
}

private fun BookingDetails.toCustomerResponse() = CustomerBookingResponse(booking.id, booking.requestId, booking.proposalId, booking.providerId,
    booking.scheduledStart, booking.scheduledEnd, booking.agreedPrice, booking.currency, booking.status.name,
    request.generalArea, request.exactLocation, request.description, booking.confirmedAt)
private fun BookingDetails.toProviderResponse() = ProviderBookingResponse(booking.id, booking.requestId, booking.proposalId, booking.customerId,
    booking.scheduledStart, booking.scheduledEnd, booking.agreedPrice, booking.currency, booking.status.name,
    request.generalArea, request.exactLocation, customerPhone, request.description, booking.confirmedAt)
private fun Jwt.accountId() = UUID.fromString(subject)
private fun <T, R> PageResponse<T>.map(transform: (T) -> R) = PageResponse(items.map(transform), page, size, totalItems, totalPages, hasNext)
