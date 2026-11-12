package com.skilledservices.platform.shared.events

import java.util.UUID

data class PlatformNotification(
    val recipientAccountId: UUID,
    val type: String,
    val title: String,
    val message: String,
    val relatedEntityType: String? = null,
    val relatedEntityId: UUID? = null,
)

fun interface PlatformNotificationPort {
    fun publish(notification: PlatformNotification)
}
