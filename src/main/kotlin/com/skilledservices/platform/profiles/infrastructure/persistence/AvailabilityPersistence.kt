package com.skilledservices.platform.profiles.infrastructure.persistence

import com.skilledservices.platform.profiles.domain.AvailabilityRange
import com.skilledservices.platform.profiles.domain.ProviderAvailability
import com.skilledservices.platform.profiles.domain.ProviderAvailabilityStore
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "provider_availability")
class ProviderAvailabilityEntity(
    @Id @Column(name = "provider_id") var providerId: UUID,
    @Column(nullable = false) var available: Boolean,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @OneToMany(mappedBy = "availability", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var ranges: MutableList<AvailabilityRangeEntity>,
)

@Entity
@Table(name = "provider_availability_ranges")
class AvailabilityRangeEntity(
    @Id var id: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16) var dayOfWeek: DayOfWeek,
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time", nullable = false) var startTime: LocalTime,
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "end_time", nullable = false) var endTime: LocalTime,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    var availability: ProviderAvailabilityEntity,
)

interface ProviderAvailabilityJpaRepository : JpaRepository<ProviderAvailabilityEntity, UUID> {
    @EntityGraph(attributePaths = ["ranges"])
    fun findAllByProviderIdIn(providerIds: Set<UUID>): List<ProviderAvailabilityEntity>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProviderAvailabilityEntity a where a.providerId = :providerId")
    fun findByProviderIdForUpdate(@Param("providerId") providerId: UUID): ProviderAvailabilityEntity?
}

@Repository
class JpaProviderAvailabilityStore(
    private val repository: ProviderAvailabilityJpaRepository,
) : ProviderAvailabilityStore {
    override fun findByProviderId(providerId: UUID) = repository.findById(providerId).orElse(null)?.toDomain()
    override fun findByProviderIds(providerIds: Set<UUID>) = if (providerIds.isEmpty()) emptyMap() else
        repository.findAllByProviderIdIn(providerIds).associate { it.providerId to it.toDomain() }
    override fun findByProviderIdForUpdate(providerId: UUID) = repository.findByProviderIdForUpdate(providerId)?.toDomain()
    override fun save(availability: ProviderAvailability) = repository.save(availability.toEntity()).toDomain()
}

private fun ProviderAvailabilityEntity.toDomain() = ProviderAvailability(
    providerId = providerId,
    available = available,
    ranges = ranges.map { AvailabilityRange(it.id, it.dayOfWeek, it.startTime, it.endTime) },
    updatedAt = updatedAt,
)

private fun ProviderAvailability.toEntity(): ProviderAvailabilityEntity {
    val entity = ProviderAvailabilityEntity(providerId, available, updatedAt, mutableListOf())
    entity.ranges = ranges.map {
        AvailabilityRangeEntity(it.id, it.dayOfWeek, it.startTime, it.endTime, entity)
    }.toMutableList()
    return entity
}
