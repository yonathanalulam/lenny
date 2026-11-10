package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.profiles.domain.AvailabilityRange
import com.skilledservices.platform.profiles.domain.InvalidAvailabilityRangeException
import com.skilledservices.platform.profiles.domain.ProfileRoleRequiredException
import com.skilledservices.platform.profiles.domain.ProviderAvailability
import com.skilledservices.platform.profiles.domain.ProviderAvailabilityStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

data class AvailabilityRangeCommand(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

data class ProviderAvailabilityCommand(
    val available: Boolean,
    val ranges: List<AvailabilityRangeCommand>,
)

@Service
class AvailabilityService(
    private val store: ProviderAvailabilityStore,
    private val identityAccountQuery: IdentityAccountQuery,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun get(providerId: UUID): ProviderAvailability? {
        requireProvider(providerId)
        return store.findByProviderId(providerId)
    }

    @Transactional
    fun put(providerId: UUID, command: ProviderAvailabilityCommand): ProviderAvailability {
        requireProvider(providerId)
        if (command.ranges.size > 100) throw InvalidAvailabilityRangeException("At most 100 weekly ranges are allowed")
        command.ranges.forEach {
            if (!it.startTime.isBefore(it.endTime)) {
                throw InvalidAvailabilityRangeException("startTime must be before endTime")
            }
        }
        val keys = command.ranges.map { Triple(it.dayOfWeek, it.startTime, it.endTime) }
        if (keys.toSet().size != keys.size) throw InvalidAvailabilityRangeException("Exact duplicate ranges are not allowed")

        return store.save(
            ProviderAvailability(
                providerId = providerId,
                available = command.available,
                ranges = command.ranges.map {
                    AvailabilityRange(UUID.randomUUID(), it.dayOfWeek, it.startTime, it.endTime)
                },
                updatedAt = clock.instant(),
            ),
        )
    }

    private fun requireProvider(accountId: UUID) {
        if (!identityAccountQuery.hasRole(accountId, AccountRole.SERVICE_PROVIDER)) throw ProfileRoleRequiredException()
    }
}
