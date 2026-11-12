package com.skilledservices.platform.feedback.infrastructure.persistence

import com.skilledservices.platform.feedback.domain.*
import com.skilledservices.platform.shared.pagination.PageSlice
import jakarta.persistence.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "booking_feedback")
class BookingFeedbackEntity(
    @Id var id: UUID,
    @Column(name = "booking_id", nullable = false) var bookingId: UUID,
    @Column(name = "rater_account_id", nullable = false) var raterAccountId: UUID,
    @Column(name = "ratee_account_id", nullable = false) var rateeAccountId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var direction: FeedbackDirection,
    @Column(nullable = false) var rating: Short,
    @Column(length = 2000) var comment: String?,
    @Column(nullable = false) var visible: Boolean,
    @Column(name = "moderation_reason", length = 1000) var moderationReason: String?,
    @Column(name = "moderated_by") var moderatedBy: UUID?,
    @Column(name = "moderated_at") var moderatedAt: Instant?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

interface FeedbackJpaRepository : JpaRepository<BookingFeedbackEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from BookingFeedbackEntity f where f.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): BookingFeedbackEntity?
    fun findByBookingIdAndDirection(bookingId: UUID, direction: FeedbackDirection): BookingFeedbackEntity?
    fun findAllByRateeAccountIdAndDirectionAndVisibleTrueOrderByCreatedAtDescIdDesc(
        rateeId: UUID, direction: FeedbackDirection, pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<BookingFeedbackEntity>
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<BookingFeedbackEntity>
}

@Repository
class JpaFeedbackStore(
    private val repository: FeedbackJpaRepository,
    private val jdbc: NamedParameterJdbcTemplate,
) : FeedbackStore, ProviderRatingQuery {
    override fun save(feedback: BookingFeedback) = repository.saveAndFlush(feedback.toEntity()).toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByBookingAndDirection(bookingId: UUID, direction: FeedbackDirection) = repository.findByBookingIdAndDirection(bookingId, direction)?.toDomain()
    override fun findPublicProviderFeedback(providerId: UUID, page: Int, size: Int) =
        repository.findAllByRateeAccountIdAndDirectionAndVisibleTrueOrderByCreatedAtDescIdDesc(
            providerId, FeedbackDirection.CUSTOMER_TO_PROVIDER, PageRequest.of(page, size),
        ).toSlice()
    override fun findAll(page: Int, size: Int) = repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size)).toSlice()
    override fun aggregates(providerIds: Set<UUID>): Map<UUID, ProviderRatingAggregate> {
        if (providerIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """select ratee_account_id, avg(rating)::double precision as average_rating, count(*) as rating_count
               from booking_feedback
               where direction = 'CUSTOMER_TO_PROVIDER' and visible = true and ratee_account_id in (:providerIds)
               group by ratee_account_id""".trimIndent(),
            mapOf("providerIds" to providerIds),
        ) { rs, _ -> ProviderRatingAggregate(rs.getObject("ratee_account_id", UUID::class.java),
            rs.getDouble("average_rating"), rs.getLong("rating_count")) }.associateBy { it.providerId }
    }
}

private fun BookingFeedbackEntity.toDomain() = BookingFeedback(id, bookingId, raterAccountId, rateeAccountId,
    direction, rating.toInt(), comment, visible, moderationReason, moderatedBy, moderatedAt, createdAt)
private fun BookingFeedback.toEntity() = BookingFeedbackEntity(id, bookingId, raterAccountId, rateeAccountId,
    direction, rating.toShort(), comment, visible, moderationReason, moderatedBy, moderatedAt, createdAt)
private fun org.springframework.data.domain.Page<BookingFeedbackEntity>.toSlice() = PageSlice(content.map { it.toDomain() }, totalElements)
