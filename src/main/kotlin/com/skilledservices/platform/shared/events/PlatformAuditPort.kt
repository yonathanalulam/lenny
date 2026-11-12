package com.skilledservices.platform.shared.events

import java.util.UUID

data class PlatformAuditCommand(
    val actorAccountId: UUID?,
    val eventType: String,
    val subjectType: String,
    val subjectId: UUID,
)

fun interface PlatformAuditPort {
    fun record(command: PlatformAuditCommand)
}
