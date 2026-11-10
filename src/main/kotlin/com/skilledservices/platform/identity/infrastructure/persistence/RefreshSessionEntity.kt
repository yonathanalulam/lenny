package com.skilledservices.platform.identity.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_sessions")
class RefreshSessionEntity(
    @Id
    var id: UUID,

    @Column(name = "account_id", nullable = false)
    var accountId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String,

    @Column(name = "credential_version", nullable = false)
    var credentialVersion: Int,

    @Column(name = "user_agent", length = 512)
    var userAgent: String?,

    @Column(name = "ip_address", length = 64)
    var ipAddress: String?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "last_used_at")
    var lastUsedAt: Instant?,

    @Column(name = "revoked_at")
    var revokedAt: Instant?,
)
