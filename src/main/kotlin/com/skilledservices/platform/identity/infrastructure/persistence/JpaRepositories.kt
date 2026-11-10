package com.skilledservices.platform.identity.infrastructure.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountEntity, UUID> {
    fun existsByEmail(email: String): Boolean
    fun existsByPhone(phone: String): Boolean
    fun findByEmail(email: String): AccountEntity?
}

interface RefreshSessionJpaRepository : JpaRepository<RefreshSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSessionEntity s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): RefreshSessionEntity?

    @Modifying(clearAutomatically = true)
    @Query(
        """
        update RefreshSessionEntity s
           set s.revokedAt = :revokedAt
         where s.accountId = :accountId
           and s.revokedAt is null
        """,
    )
    fun revokeAllForAccount(
        @Param("accountId") accountId: UUID,
        @Param("revokedAt") revokedAt: Instant,
    ): Int
}
