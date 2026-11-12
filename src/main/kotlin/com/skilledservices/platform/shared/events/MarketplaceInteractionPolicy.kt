package com.skilledservices.platform.shared.events

import java.util.UUID

fun interface MarketplaceInteractionPolicy {
    fun requireAllowed(firstAccountId: UUID, secondAccountId: UUID)
}
