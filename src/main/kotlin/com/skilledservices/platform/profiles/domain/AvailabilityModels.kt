package com.skilledservices.platform.profiles.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class AvailabilityRange(
    val id: UUID,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

data class ProviderAvailability(
    val providerId: UUID,
    val available: Boolean,
    val ranges: List<AvailabilityRange>,
    val updatedAt: Instant,
)

interface ProviderAvailabilityStore {
    fun findByProviderId(providerId: UUID): ProviderAvailability?
    fun save(availability: ProviderAvailability): ProviderAvailability
}
