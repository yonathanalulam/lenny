package com.skilledservices.platform.booking.application

import com.skilledservices.platform.booking.domain.*
import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.profiles.application.*
import com.skilledservices.platform.shared.api.PageResponse
import com.skilledservices.platform.shared.events.PlatformNotification
import com.skilledservices.platform.shared.events.PlatformNotificationPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

data class ConfirmBookingCommand(val scheduledStart: OffsetDateTime, val scheduledEnd: OffsetDateTime)
data class CancelBookingCommand(val reason: String?)
data class BookingDetails(val booking: Booking, val request: BookingRequestSnapshot, val customerPhone: String?, val history: List<BookingStatusHistory>)

interface ProviderBookingAvailabilityQuery {
    fun hasBlockingConflict(providerId: UUID, start: Instant, end: Instant): Boolean
}

interface BookingDisputePort {
    fun openDispute(actorId: UUID, bookingId: UUID, supportCaseId: UUID)
}

data class BookingFeedbackContext(
    val bookingId: UUID,
    val customerId: UUID,
    val providerId: UUID,
    val status: BookingStatus,
)

interface BookingFeedbackQuery {
    fun context(bookingId: UUID): BookingFeedbackContext?
}

@Service
class BookingService(
    private val bookings: BookingStore,
    private val history: BookingStatusHistoryStore,
    private val selections: BookingSelectionPort,
    private val schedules: ProviderScheduleAccess,
    private val customers: CustomerEligibilityService,
    private val providers: ProviderEligibilityService,
    private val identity: IdentityAccountQuery,
    private val notifications: PlatformNotificationPort,
    private val clock: Clock,
) : ProviderBookingAvailabilityQuery, BookingDisputePort, BookingFeedbackQuery {
    @Transactional
    fun confirm(customerId: UUID, proposalId: UUID, command: ConfirmBookingCommand): BookingDetails {
        val start = command.scheduledStart.toInstant()
        val end = command.scheduledEnd.toInstant()
        if (!start.isBefore(end)) throw BookingScheduleInvalidException()
        if (identity.accountStatus(customerId) != AccountStatus.ACTIVE || !customers.canCreateMarketplaceRequest(customerId)) throw BookingNotOwnedException()
        val selection = selections.lockForAcceptance(customerId, proposalId)
        bookings.findByRequestId(selection.requestId)?.let { existing ->
            if (existing.proposalId == proposalId) return details(existing)
            throw RequestAlreadyHasBookingException()
        }
        if (selection.proposalStatus != "PENDING" || selection.requestStatus != "OPEN") throw ProposalNotAcceptableException()
        if (identity.accountStatus(selection.providerId) != AccountStatus.ACTIVE || !providers.isMarketplaceEligible(selection.providerId)) throw ProviderNotAvailableException()
        val schedule = schedules.lock(selection.providerId) ?: throw ProviderNotAvailableException()
        if (!schedule.available || !withinDeclaredAvailability(schedule, start, end)) throw ProviderNotAvailableException()
        if (bookings.existsBlockingConflict(selection.providerId, start, end)) throw BookingScheduleConflictException()
        val now = clock.instant()
        val booking = try {
            bookings.save(Booking(UUID.randomUUID(), selection.requestId, selection.proposalId, selection.customerId,
                selection.providerId, start, end, selection.proposedPrice, "ETB", BookingStatus.CONFIRMED, now))
        } catch (_: DataIntegrityViolationException) {
            bookings.findByRequestId(selection.requestId)?.takeIf { it.proposalId == proposalId }?.let { return details(it) }
            throw RequestAlreadyHasBookingException()
        }
        selections.finalizeSelection(selection, booking.id, now)
        history.save(BookingStatusHistory(UUID.randomUUID(), booking.id, null, BookingStatus.CONFIRMED, customerId, BookingActorContext.CUSTOMER, now, null))
        notifications.publish(PlatformNotification(booking.providerId, "BOOKING_CONFIRMED", "Booking confirmed",
            "A customer confirmed your proposal.", "BOOKING", booking.id))
        return details(booking)
    }

    @Transactional(readOnly = true)
    fun listCustomer(customerId: UUID, status: BookingStatus?, page: Int, size: Int) =
        page(bookings.findByCustomerId(customerId).filter { status == null || it.status == status }.map(::details), page, size)

    @Transactional(readOnly = true)
    fun getCustomer(customerId: UUID, bookingId: UUID): BookingDetails {
        val booking = bookings.findById(bookingId) ?: throw BookingNotFoundException()
        if (booking.customerId != customerId) throw BookingNotOwnedException()
        return details(booking)
    }

    @Transactional(readOnly = true)
    fun listProvider(providerId: UUID, status: BookingStatus?, page: Int, size: Int) =
        page(bookings.findByProviderId(providerId).filter { status == null || it.status == status }.map(::details), page, size)

    @Transactional(readOnly = true)
    fun getProvider(providerId: UUID, bookingId: UUID): BookingDetails {
        val booking = bookings.findById(bookingId) ?: throw BookingNotFoundException()
        if (booking.providerId != providerId) throw BookingNotOwnedException()
        return details(booking)
    }

    @Transactional
    fun startProvider(providerId: UUID, bookingId: UUID): BookingDetails {
        requireActive(providerId)
        val booking = lockedProviderBooking(providerId, bookingId)
        val transition = booking.start(clock.instant())
        val result = persistTransition(transition, providerId, BookingActorContext.PROVIDER, null)
        if (transition.changed) notifications.publish(PlatformNotification(booking.customerId, "BOOKING_STARTED", "Service started",
            "Your provider started the booked service.", "BOOKING", booking.id))
        return result
    }

    @Transactional
    fun reportComplete(providerId: UUID, bookingId: UUID): BookingDetails {
        requireActive(providerId)
        val booking = lockedProviderBooking(providerId, bookingId)
        val transition = booking.reportComplete(clock.instant())
        val result = persistTransition(transition, providerId, BookingActorContext.PROVIDER, null)
        if (transition.changed) notifications.publish(PlatformNotification(booking.customerId, "COMPLETION_REPORTED", "Completion reported",
            "Your provider reported the service complete.", "BOOKING", booking.id))
        return result
    }

    @Transactional
    fun confirmCompletion(customerId: UUID, bookingId: UUID): BookingDetails {
        requireActive(customerId)
        val booking = lockedCustomerBooking(customerId, bookingId)
        val transition = booking.confirmCompletion(clock.instant())
        val result = persistTransition(transition, customerId, BookingActorContext.CUSTOMER, null)
        if (transition.changed) notifications.publish(PlatformNotification(booking.providerId, "COMPLETION_CONFIRMED", "Completion confirmed",
            "The customer confirmed service completion.", "BOOKING", booking.id))
        return result
    }

    @Transactional
    fun cancelCustomer(customerId: UUID, bookingId: UUID, command: CancelBookingCommand): BookingDetails {
        requireActive(customerId)
        val reason = command.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (reason != null && reason.length > 500) throw BookingCancellationReasonInvalidException()
        val booking = lockedCustomerBooking(customerId, bookingId)
        val transition = booking.cancel(customerId, reason, clock.instant())
        val result = persistTransition(transition, customerId, BookingActorContext.CUSTOMER, reason)
        if (transition.changed) notifications.publish(PlatformNotification(booking.providerId, "BOOKING_CANCELLED", "Booking cancelled",
            "The customer cancelled a confirmed Booking.", "BOOKING", booking.id))
        return result
    }

    @Transactional(readOnly = true)
    override fun hasBlockingConflict(providerId: UUID, start: Instant, end: Instant) = bookings.existsBlockingConflict(providerId, start, end)

    @Transactional(readOnly = true)
    override fun context(bookingId: UUID) = bookings.findById(bookingId)?.let {
        BookingFeedbackContext(it.id, it.customerId, it.providerId, it.status)
    }

    @Transactional
    override fun openDispute(actorId: UUID, bookingId: UUID, supportCaseId: UUID) {
        requireActive(actorId)
        val booking = bookings.findByIdForUpdate(bookingId) ?: throw BookingNotFoundException()
        val context = when (actorId) {
            booking.customerId -> BookingActorContext.CUSTOMER
            booking.providerId -> BookingActorContext.PROVIDER
            else -> throw BookingNotOwnedException()
        }
        val transition = booking.openReview()
        persistTransition(transition, actorId, context, "Support case $supportCaseId")
        val recipient = if (actorId == booking.customerId) booking.providerId else booking.customerId
        if (transition.changed) notifications.publish(PlatformNotification(recipient, "BOOKING_UNDER_REVIEW", "Booking under review",
            "A dispute moved this Booking under review.", "BOOKING", booking.id))
    }

    private fun details(booking: Booking) = BookingDetails(booking, selections.requestSnapshot(booking.requestId), identity.accountPhone(booking.customerId), history.findByBookingId(booking.id))

    private fun lockedProviderBooking(providerId: UUID, bookingId: UUID): Booking {
        val booking = bookings.findByIdForUpdate(bookingId) ?: throw BookingNotFoundException()
        if (booking.providerId != providerId) throw BookingNotOwnedException()
        return booking
    }

    private fun lockedCustomerBooking(customerId: UUID, bookingId: UUID): Booking {
        val booking = bookings.findByIdForUpdate(bookingId) ?: throw BookingNotFoundException()
        if (booking.customerId != customerId) throw BookingNotOwnedException()
        return booking
    }

    private fun requireActive(accountId: UUID) {
        if (identity.accountStatus(accountId) != AccountStatus.ACTIVE) throw BookingActorInactiveException()
    }

    private fun persistTransition(transition: BookingTransition, actorId: UUID, context: BookingActorContext, reason: String?): BookingDetails {
        if (!transition.changed) return details(transition.booking)
        val saved = bookings.save(transition.booking)
        history.save(BookingStatusHistory(UUID.randomUUID(), saved.id, transition.fromStatus, saved.status, actorId, context, clock.instant(), reason))
        return details(saved)
    }

    private fun withinDeclaredAvailability(schedule: com.skilledservices.platform.profiles.domain.ProviderAvailability, start: Instant, end: Instant): Boolean {
        val localStart = start.atZone(ADDIS_ABABA)
        val localEnd = end.atZone(ADDIS_ABABA)
        if (localStart.toLocalDate() != localEnd.toLocalDate()) return false
        return schedule.ranges.any { it.dayOfWeek == localStart.dayOfWeek && it.startTime <= localStart.toLocalTime() && it.endTime >= localEnd.toLocalTime() }
    }

    private fun <T> page(items: List<T>, page: Int, size: Int): PageResponse<T> {
        if (page < 0 || size !in 1..100) throw BookingScheduleInvalidException()
        val from = (page * size).coerceAtMost(items.size); val to = (from + size).coerceAtMost(items.size)
        val pages = if (items.isEmpty()) 0 else (items.size + size - 1) / size
        return PageResponse(items.subList(from, to), page, size, items.size.toLong(), pages, page + 1 < pages)
    }

    companion object { private val ADDIS_ABABA = ZoneId.of("Africa/Addis_Ababa") }
}
