package com.skilledservices.platform.booking.application

import com.skilledservices.platform.booking.domain.*
import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.profiles.application.*
import com.skilledservices.platform.shared.api.PageResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

data class ConfirmBookingCommand(val scheduledStart: OffsetDateTime, val scheduledEnd: OffsetDateTime)
data class BookingDetails(val booking: Booking, val request: BookingRequestSnapshot, val customerPhone: String?)

interface ProviderBookingAvailabilityQuery {
    fun hasBlockingConflict(providerId: UUID, start: Instant, end: Instant): Boolean
}

@Service
class BookingService(
    private val bookings: BookingStore,
    private val selections: BookingSelectionPort,
    private val schedules: ProviderScheduleAccess,
    private val customers: CustomerEligibilityService,
    private val providers: ProviderEligibilityService,
    private val identity: IdentityAccountQuery,
    private val clock: Clock,
) : ProviderBookingAvailabilityQuery {
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
        return details(booking)
    }

    @Transactional(readOnly = true)
    fun listCustomer(customerId: UUID, page: Int, size: Int) = page(bookings.findByCustomerId(customerId).map(::details), page, size)

    @Transactional(readOnly = true)
    fun getCustomer(customerId: UUID, bookingId: UUID): BookingDetails {
        val booking = bookings.findById(bookingId) ?: throw BookingNotFoundException()
        if (booking.customerId != customerId) throw BookingNotOwnedException()
        return details(booking)
    }

    @Transactional(readOnly = true)
    fun listProvider(providerId: UUID, page: Int, size: Int) = page(bookings.findByProviderId(providerId).map(::details), page, size)

    @Transactional(readOnly = true)
    fun getProvider(providerId: UUID, bookingId: UUID): BookingDetails {
        val booking = bookings.findById(bookingId) ?: throw BookingNotFoundException()
        if (booking.providerId != providerId) throw BookingNotOwnedException()
        return details(booking)
    }

    @Transactional(readOnly = true)
    override fun hasBlockingConflict(providerId: UUID, start: Instant, end: Instant) = bookings.existsBlockingConflict(providerId, start, end)

    private fun details(booking: Booking) = BookingDetails(booking, selections.requestSnapshot(booking.requestId), identity.accountPhone(booking.customerId))

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
