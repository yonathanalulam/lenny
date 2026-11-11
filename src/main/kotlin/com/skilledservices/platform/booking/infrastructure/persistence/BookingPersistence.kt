package com.skilledservices.platform.booking.infrastructure.persistence

import com.skilledservices.platform.booking.domain.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "bookings")
class BookingEntity(
    @Id var id: UUID,
    @Column(name = "request_id", nullable = false) var requestId: UUID,
    @Column(name = "proposal_id", nullable = false) var proposalId: UUID,
    @Column(name = "customer_id", nullable = false) var customerId: UUID,
    @Column(name = "provider_id", nullable = false) var providerId: UUID,
    @Column(name = "scheduled_start", nullable = false) var scheduledStart: Instant,
    @Column(name = "scheduled_end", nullable = false) var scheduledEnd: Instant,
    @Column(name = "agreed_price", nullable = false, precision = 12, scale = 2) var agreedPrice: BigDecimal,
    @Column(nullable = false, length = 3) var currency: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: BookingStatus,
    @Column(name = "confirmed_at", nullable = false) var confirmedAt: Instant,
    @Column(name = "started_at") var startedAt: Instant?,
    @Column(name = "provider_reported_complete_at") var providerReportedCompleteAt: Instant?,
    @Column(name = "completed_at") var completedAt: Instant?,
    @Column(name = "cancelled_at") var cancelledAt: Instant?,
    @Column(name = "cancellation_actor_id") var cancellationActorId: UUID?,
    @Column(name = "cancellation_reason", length = 500) var cancellationReason: String?,
)

@Entity
@Table(name = "booking_status_history")
class BookingStatusHistoryEntity(
    @Id var id: UUID,
    @Column(name = "booking_id", nullable = false) var bookingId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "from_status", length = 32) var fromStatus: BookingStatus?,
    @Enumerated(EnumType.STRING) @Column(name = "to_status", nullable = false, length = 32) var toStatus: BookingStatus,
    @Column(name = "actor_account_id", nullable = false) var actorAccountId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "actor_context", nullable = false, length = 32) var actorContext: BookingActorContext,
    @Column(name = "occurred_at", nullable = false) var occurredAt: Instant,
    @Column(length = 500) var reason: String?,
)

interface BookingJpaRepository : JpaRepository<BookingEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): BookingEntity?
    fun findByRequestId(requestId: UUID): BookingEntity?
    fun findAllByCustomerIdOrderByConfirmedAtDesc(customerId: UUID): List<BookingEntity>
    fun findAllByProviderIdOrderByConfirmedAtDesc(providerId: UUID): List<BookingEntity>
    @Query("select (count(b) > 0) from BookingEntity b where b.providerId = :providerId and b.status <> com.skilledservices.platform.booking.domain.BookingStatus.CANCELLED and b.scheduledStart < :end and b.scheduledEnd > :start")
    fun existsConflict(@Param("providerId") providerId: UUID, @Param("start") start: Instant, @Param("end") end: Instant): Boolean
}

interface BookingStatusHistoryJpaRepository : JpaRepository<BookingStatusHistoryEntity, UUID> {
    fun findAllByBookingIdOrderByOccurredAtAscIdAsc(bookingId: UUID): List<BookingStatusHistoryEntity>
}

@Repository
class JpaBookingStore(private val repository: BookingJpaRepository) : BookingStore {
    override fun save(booking: Booking) = repository.saveAndFlush(booking.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findByRequestId(requestId)?.toDomain()
    override fun findByCustomerId(customerId: UUID) = repository.findAllByCustomerIdOrderByConfirmedAtDesc(customerId).map { it.toDomain() }
    override fun findByProviderId(providerId: UUID) = repository.findAllByProviderIdOrderByConfirmedAtDesc(providerId).map { it.toDomain() }
    override fun existsBlockingConflict(providerId: UUID, start: Instant, end: Instant) = repository.existsConflict(providerId, start, end)
}

@Repository
class JpaBookingStatusHistoryStore(private val repository: BookingStatusHistoryJpaRepository) : BookingStatusHistoryStore {
    override fun save(history: BookingStatusHistory) = repository.save(history.toEntity()).toDomain()
    override fun findByBookingId(bookingId: UUID) = repository.findAllByBookingIdOrderByOccurredAtAscIdAsc(bookingId).map { it.toDomain() }
}

private fun BookingEntity.toDomain() = Booking(id, requestId, proposalId, customerId, providerId, scheduledStart, scheduledEnd, agreedPrice, currency, status,
    confirmedAt, startedAt, providerReportedCompleteAt, completedAt, cancelledAt, cancellationActorId, cancellationReason)
private fun Booking.toEntity() = BookingEntity(id, requestId, proposalId, customerId, providerId, scheduledStart, scheduledEnd, agreedPrice, currency, status,
    confirmedAt, startedAt, providerReportedCompleteAt, completedAt, cancelledAt, cancellationActorId, cancellationReason)
private fun BookingStatusHistoryEntity.toDomain() = BookingStatusHistory(id, bookingId, fromStatus, toStatus, actorAccountId, actorContext, occurredAt, reason)
private fun BookingStatusHistory.toEntity() = BookingStatusHistoryEntity(id, bookingId, fromStatus, toStatus, actorAccountId, actorContext, occurredAt, reason)
