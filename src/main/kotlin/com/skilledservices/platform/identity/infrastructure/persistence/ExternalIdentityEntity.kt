package com.skilledservices.platform.identity.infrastructure.persistence

import com.skilledservices.platform.identity.domain.ExternalIdentityProvider
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "external_identities")
class ExternalIdentityEntity(
    @Id var id: UUID,
    @Column(name = "account_id", nullable = false) var accountId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var provider: ExternalIdentityProvider,
    @Column(name = "provider_subject", nullable = false, length = 255) var providerSubject: String,
    @Column(name = "verified_email", nullable = false, length = 320) var verifiedEmail: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "last_login_at", nullable = false) var lastLoginAt: Instant,
)
