package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.profiles.domain.AvailabilityRange
import com.skilledservices.platform.profiles.domain.ProviderAvailabilityStore
import com.skilledservices.platform.profiles.domain.ProviderProfileStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class DiscoverableProviderProfile(
    val providerId: UUID,
    val professionalDescription: String,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val portfolioSummary: String?,
    val profilePhotoAvailable: Boolean,
    val profileUpdatedAt: Instant,
    val available: Boolean,
    val availabilityRanges: List<AvailabilityRange>,
)

interface ProviderDiscoveryProfileQuery {
    fun findAll(): List<DiscoverableProviderProfile>
    fun findByProviderId(providerId: UUID): DiscoverableProviderProfile?
}

@Service
class ProfileBackedProviderDiscoveryQuery(
    private val profiles: ProviderProfileStore,
    private val availability: ProviderAvailabilityStore,
) : ProviderDiscoveryProfileQuery {
    @Transactional(readOnly = true)
    override fun findAll(): List<DiscoverableProviderProfile> {
        val all = profiles.findAll()
        val schedules = availability.findByProviderIds(all.map { it.accountId }.toSet())
        return all.map { toPublicSource(it, schedules[it.accountId]) }
    }

    @Transactional(readOnly = true)
    override fun findByProviderId(providerId: UUID) = profiles.findByAccountId(providerId)?.let { toPublicSource(it, availability.findByProviderId(providerId)) }

    private fun toPublicSource(profile: com.skilledservices.platform.profiles.domain.ProviderProfile,
        schedule: com.skilledservices.platform.profiles.domain.ProviderAvailability?): DiscoverableProviderProfile {
        return DiscoverableProviderProfile(
            profile.accountId, profile.professionalDescription, profile.skills, profile.experienceYears,
            profile.serviceArea, profile.pricingSummary, profile.portfolioSummary, profile.profilePhotoObjectId != null,
            profile.updatedAt, schedule?.available == true, schedule?.ranges.orEmpty(),
        )
    }
}
