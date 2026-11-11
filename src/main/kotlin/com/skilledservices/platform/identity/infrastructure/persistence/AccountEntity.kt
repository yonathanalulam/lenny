package com.skilledservices.platform.identity.infrastructure.persistence

import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "accounts")
class AccountEntity(
    @Id
    var id: UUID,

    @Column(nullable = false, length = 320)
    var email: String,

    @Column(length = 32)
    var phone: String?,

    @Column(name = "password_hash", length = 100)
    var passwordHash: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: AccountStatus,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "account_roles", joinColumns = [JoinColumn(name = "account_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var roles: MutableSet<AccountRole>,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean,

    @Column(name = "phone_verified", nullable = false)
    var phoneVerified: Boolean,

    @Column(name = "credential_version", nullable = false)
    var credentialVersion: Int,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
