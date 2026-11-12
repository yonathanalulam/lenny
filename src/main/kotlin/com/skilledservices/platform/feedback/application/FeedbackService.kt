package com.skilledservices.platform.feedback.application

import com.skilledservices.platform.booking.application.BookingFeedbackQuery
import com.skilledservices.platform.booking.domain.BookingStatus
import com.skilledservices.platform.feedback.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import com.skilledservices.platform.shared.pagination.PageSlice
import com.skilledservices.platform.shared.events.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class SubmitFeedbackCommand(val rating: Int, val comment: String?)
data class ModerateFeedbackCommand(val visible: Boolean, val reason: String?)

@Service
class FeedbackService(
    private val store: FeedbackStore,
    private val bookings: BookingFeedbackQuery,
    private val notifications: PlatformNotificationPort,
    private val audit: PlatformAuditPort,
    private val clock: Clock,
) {
    @Transactional
    fun customerToProvider(customerId: UUID, bookingId: UUID, command: SubmitFeedbackCommand) =
        submit(customerId, bookingId, FeedbackDirection.CUSTOMER_TO_PROVIDER, command)

    @Transactional
    fun providerToCustomer(providerId: UUID, bookingId: UUID, command: SubmitFeedbackCommand) =
        submit(providerId, bookingId, FeedbackDirection.PROVIDER_TO_CUSTOMER, command)

    @Transactional(readOnly = true)
    fun publicProviderReviews(providerId: UUID, page: Int, size: Int) =
        store.findPublicProviderFeedback(providerId, validPage(page), validSize(size)).toPage(page, size)

    @Transactional(readOnly = true)
    fun adminList(page: Int, size: Int) = store.findAll(validPage(page), validSize(size)).toPage(page, size)

    @Transactional
    fun moderate(adminId: UUID, feedbackId: UUID, command: ModerateFeedbackCommand): BookingFeedback {
        val feedback = store.findByIdForUpdate(feedbackId) ?: throw FeedbackNotFoundException()
        if (feedback.visible == command.visible) return feedback
        val now = clock.instant()
        val updated = if (command.visible) {
            feedback.copy(visible = true, moderationReason = null, moderatedBy = null, moderatedAt = null)
        } else {
            val reason = command.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
                ?: throw FeedbackInvalidException("A moderation reason is required when hiding feedback")
            feedback.copy(visible = false, moderationReason = reason, moderatedBy = adminId, moderatedAt = now)
        }
        val saved = store.save(updated)
        audit.record(PlatformAuditCommand(adminId, if (saved.visible) "FEEDBACK_RESTORED" else "FEEDBACK_HIDDEN", "FEEDBACK", saved.id))
        return saved
    }

    private fun submit(actorId: UUID, bookingId: UUID, direction: FeedbackDirection, command: SubmitFeedbackCommand): BookingFeedback {
        if (command.rating !in 1..5) throw FeedbackInvalidException("Rating must be between 1 and 5")
        val comment = command.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > 2000) throw FeedbackInvalidException("Comment must not exceed 2000 characters")
        val booking = bookings.context(bookingId) ?: throw FeedbackBookingNotFoundException()
        if (booking.status != BookingStatus.COMPLETED) throw FeedbackNotAllowedException()
        val ratee = when (direction) {
            FeedbackDirection.CUSTOMER_TO_PROVIDER -> {
                if (actorId != booking.customerId) throw FeedbackNotAllowedException()
                booking.providerId
            }
            FeedbackDirection.PROVIDER_TO_CUSTOMER -> {
                if (actorId != booking.providerId) throw FeedbackNotAllowedException()
                booking.customerId
            }
        }
        store.findByBookingAndDirection(bookingId, direction)?.let { throw FeedbackAlreadyExistsException() }
        val feedback = try {
            store.save(BookingFeedback(UUID.randomUUID(), bookingId, actorId, ratee, direction, command.rating,
                comment, true, null, null, null, clock.instant()))
        } catch (_: DataIntegrityViolationException) {
            throw FeedbackAlreadyExistsException()
        }
        notifications.publish(PlatformNotification(ratee, "FEEDBACK_RECEIVED", "Feedback received",
            "A completed service received new feedback.", "FEEDBACK", feedback.id))
        audit.record(PlatformAuditCommand(actorId, "FEEDBACK_SUBMITTED", "FEEDBACK", feedback.id))
        return feedback
    }
}

private fun validPage(page: Int): Int = page.also { if (it < 0) throw FeedbackInvalidException("page must be non-negative") }
private fun validSize(size: Int): Int = size.also { if (it !in 1..100) throw FeedbackInvalidException("size must be between 1 and 100") }
private fun <T> PageSlice<T>.toPage(page: Int, size: Int): PageResponse<T> {
    val pages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
    return PageResponse(items, page, size, totalItems, pages, page + 1 < pages)
}
