package com.skilledservices.platform.booking.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class BookingStatus { CONFIRMED }

data class Booking(
    val id: UUID,
    val requestId: UUID,
    val proposalId: UUID,
    val customerId: UUID,
    val providerId: UUID,
    val scheduledStart: Instant,
    val scheduledEnd: Instant,
    val agreedPrice: BigDecimal,
    val currency: String,
    val status: BookingStatus,
    val confirmedAt: Instant,
)

interface BookingStore {
    fun save(booking: Booking): Booking
    fun findById(id: UUID): Booking?
    fun findByRequestId(requestId: UUID): Booking?
    fun findByCustomerId(customerId: UUID): List<Booking>
    fun findByProviderId(providerId: UUID): List<Booking>
    fun existsBlockingConflict(providerId: UUID, start: Instant, end: Instant): Boolean
}

data class BookingSelection(
    val proposalId: UUID,
    val requestId: UUID,
    val customerId: UUID,
    val providerId: UUID,
    val proposedPrice: BigDecimal,
    val requestStatus: String,
    val proposalStatus: String,
)

data class BookingRequestSnapshot(
    val requestId: UUID,
    val generalArea: String,
    val exactLocation: String,
    val description: String,
)

interface BookingSelectionPort {
    fun lockForAcceptance(customerId: UUID, proposalId: UUID): BookingSelection
    fun finalizeSelection(selection: BookingSelection, bookingId: UUID, occurredAt: Instant)
    fun requestSnapshot(requestId: UUID): BookingRequestSnapshot
}

open class BookingException(val code: String, message: String) : RuntimeException(message)
class ProposalNotAcceptableException : BookingException("PROPOSAL_NOT_ACCEPTABLE", "Proposal cannot be accepted")
class RequestAlreadyHasBookingException : BookingException("REQUEST_ALREADY_HAS_BOOKING", "Service request already has a Booking")
class BookingScheduleInvalidException : BookingException("BOOKING_SCHEDULE_INVALID", "Booking start must be before end")
class ProviderNotAvailableException : BookingException("PROVIDER_NOT_AVAILABLE", "Provider is not available for the requested interval")
class BookingScheduleConflictException : BookingException("BOOKING_SCHEDULE_CONFLICT", "Provider already has a blocking Booking in this interval")
class BookingNotFoundException : BookingException("BOOKING_NOT_FOUND", "Booking was not found")
class BookingNotOwnedException : BookingException("BOOKING_NOT_OWNED", "Booking is not accessible to this account")
