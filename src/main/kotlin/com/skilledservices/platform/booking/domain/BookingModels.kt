package com.skilledservices.platform.booking.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class BookingStatus { CONFIRMED, IN_PROGRESS, COMPLETION_PENDING, COMPLETED, UNDER_REVIEW, CANCELLED }
enum class BookingActorContext { CUSTOMER, PROVIDER, SYSTEM }

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
    val startedAt: Instant? = null,
    val providerReportedCompleteAt: Instant? = null,
    val completedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val cancellationActorId: UUID? = null,
    val cancellationReason: String? = null,
) {
    fun start(now: Instant) = when (status) {
        BookingStatus.IN_PROGRESS -> BookingTransition(this, null)
        BookingStatus.CONFIRMED -> BookingTransition(copy(status = BookingStatus.IN_PROGRESS, startedAt = now), BookingStatus.CONFIRMED)
        else -> throw BookingInvalidTransitionException(status, BookingStatus.IN_PROGRESS)
    }

    fun reportComplete(now: Instant) = when (status) {
        BookingStatus.COMPLETION_PENDING -> BookingTransition(this, null)
        BookingStatus.IN_PROGRESS -> BookingTransition(copy(status = BookingStatus.COMPLETION_PENDING, providerReportedCompleteAt = now), BookingStatus.IN_PROGRESS)
        else -> throw BookingInvalidTransitionException(status, BookingStatus.COMPLETION_PENDING)
    }

    fun confirmCompletion(now: Instant) = when (status) {
        BookingStatus.COMPLETED -> BookingTransition(this, null)
        BookingStatus.COMPLETION_PENDING -> BookingTransition(copy(status = BookingStatus.COMPLETED, completedAt = now), BookingStatus.COMPLETION_PENDING)
        else -> throw BookingInvalidTransitionException(status, BookingStatus.COMPLETED)
    }

    fun cancel(actorId: UUID, reason: String?, now: Instant) = when (status) {
        BookingStatus.CANCELLED -> BookingTransition(this, null)
        BookingStatus.CONFIRMED -> BookingTransition(copy(status = BookingStatus.CANCELLED, cancelledAt = now, cancellationActorId = actorId, cancellationReason = reason), BookingStatus.CONFIRMED)
        else -> throw BookingInvalidTransitionException(status, BookingStatus.CANCELLED)
    }
}

data class BookingTransition(val booking: Booking, val fromStatus: BookingStatus?) {
    val changed: Boolean get() = fromStatus != null
}

data class BookingStatusHistory(
    val id: UUID,
    val bookingId: UUID,
    val fromStatus: BookingStatus?,
    val toStatus: BookingStatus,
    val actorAccountId: UUID,
    val actorContext: BookingActorContext,
    val occurredAt: Instant,
    val reason: String?,
)

interface BookingStore {
    fun save(booking: Booking): Booking
    fun findById(id: UUID): Booking?
    fun findByIdForUpdate(id: UUID): Booking?
    fun findByRequestId(requestId: UUID): Booking?
    fun findByCustomerId(customerId: UUID): List<Booking>
    fun findByProviderId(providerId: UUID): List<Booking>
    fun existsBlockingConflict(providerId: UUID, start: Instant, end: Instant): Boolean
}

interface BookingStatusHistoryStore {
    fun save(history: BookingStatusHistory): BookingStatusHistory
    fun findByBookingId(bookingId: UUID): List<BookingStatusHistory>
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
class BookingInvalidTransitionException(from: BookingStatus, to: BookingStatus) :
    BookingException("BOOKING_INVALID_TRANSITION", "Booking cannot transition from $from to $to")
class BookingActorInactiveException : BookingException("BOOKING_ACTOR_INACTIVE", "The account cannot perform Booking lifecycle actions")
class BookingCancellationReasonInvalidException : BookingException("BOOKING_CANCELLATION_REASON_INVALID", "Cancellation reason must contain at most 500 characters")
