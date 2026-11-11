package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.profiles.domain.ProviderAvailability
import com.skilledservices.platform.profiles.domain.ProviderAvailabilityStore
import org.springframework.stereotype.Service
import java.util.UUID

interface ProviderScheduleAccess { fun lock(providerId: UUID): ProviderAvailability? }

@Service
class ProfileProviderScheduleAccess(private val store: ProviderAvailabilityStore) : ProviderScheduleAccess {
    override fun lock(providerId: UUID) = store.findByProviderIdForUpdate(providerId)
}
