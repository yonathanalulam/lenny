package com.skilledservices.platform.feedback.domain

import com.skilledservices.platform.shared.pagination.PageSlice
import java.time.Instant
import java.util.UUID

enum class FeedbackDirection { CUSTOMER_TO_PROVIDER, PROVIDER_TO_CUSTOMER }

data class BookingFeedback(
    val id: UUID,
    val bookingId: UUID,
    val raterAccountId: UUID,
    val rateeAccountId: UUID,
    val direction: FeedbackDirection,
    val rating: Int,
    val comment: String?,
    val visible: Boolean,
    val moderationReason: String?,
    val moderatedBy: UUID?,
    val moderatedAt: Instant?,
    val createdAt: Instant,
)

data class ProviderRatingAggregate(val providerId: UUID, val averageRating: Double?, val ratingCount: Long)

interface FeedbackStore {
    fun save(feedback: BookingFeedback): BookingFeedback
    fun findByIdForUpdate(id: UUID): BookingFeedback?
    fun findByBookingAndDirection(bookingId: UUID, direction: FeedbackDirection): BookingFeedback?
    fun findPublicProviderFeedback(providerId: UUID, page: Int, size: Int): PageSlice<BookingFeedback>
    fun findAll(page: Int, size: Int): PageSlice<BookingFeedback>
    fun aggregates(providerIds: Set<UUID>): Map<UUID, ProviderRatingAggregate>
}

interface ProviderRatingQuery {
    fun aggregates(providerIds: Set<UUID>): Map<UUID, ProviderRatingAggregate>
}

open class FeedbackException(val code: String, message: String) : RuntimeException(message)
class FeedbackBookingNotFoundException : FeedbackException("FEEDBACK_BOOKING_NOT_FOUND", "Booking was not found")
class FeedbackNotAllowedException : FeedbackException("FEEDBACK_NOT_ALLOWED", "Feedback is allowed only for the correct participant after Booking completion")
class FeedbackAlreadyExistsException : FeedbackException("FEEDBACK_ALREADY_EXISTS", "Feedback already exists for this Booking and direction")
class FeedbackInvalidException(message: String) : FeedbackException("FEEDBACK_INVALID", message)
class FeedbackNotFoundException : FeedbackException("FEEDBACK_NOT_FOUND", "Feedback was not found")
