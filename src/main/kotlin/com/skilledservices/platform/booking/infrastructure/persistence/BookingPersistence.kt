package com.skilledservices.platform.booking.infrastructure.persistence

import com.skilledservices.platform.booking.domain.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
)

interface BookingJpaRepository : JpaRepository<BookingEntity, UUID> {
    fun findByRequestId(requestId: UUID): BookingEntity?
    fun findAllByCustomerIdOrderByConfirmedAtDesc(customerId: UUID): List<BookingEntity>
    fun findAllByProviderIdOrderByConfirmedAtDesc(providerId: UUID): List<BookingEntity>
    @Query("select (count(b) > 0) from BookingEntity b where b.providerId = :providerId and b.scheduledStart < :end and b.scheduledEnd > :start")
    fun existsConflict(@Param("providerId") providerId: UUID, @Param("start") start: Instant, @Param("end") end: Instant): Boolean
}

@Repository
class JpaBookingStore(private val repository: BookingJpaRepository) : BookingStore {
    override fun save(booking: Booking) = repository.saveAndFlush(booking.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findByRequestId(requestId)?.toDomain()
    override fun findByCustomerId(customerId: UUID) = repository.findAllByCustomerIdOrderByConfirmedAtDesc(customerId).map { it.toDomain() }
    override fun findByProviderId(providerId: UUID) = repository.findAllByProviderIdOrderByConfirmedAtDesc(providerId).map { it.toDomain() }
    override fun existsBlockingConflict(providerId: UUID, start: Instant, end: Instant) = repository.existsConflict(providerId, start, end)
}

private fun BookingEntity.toDomain() = Booking(id, requestId, proposalId, customerId, providerId, scheduledStart, scheduledEnd, agreedPrice, currency, status, confirmedAt)
private fun Booking.toEntity() = BookingEntity(id, requestId, proposalId, customerId, providerId, scheduledStart, scheduledEnd, agreedPrice, currency, status, confirmedAt)
