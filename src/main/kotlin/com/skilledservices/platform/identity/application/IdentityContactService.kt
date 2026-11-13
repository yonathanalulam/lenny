package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.AccountNotFoundException
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.identity.domain.ContactEmailMismatchException
import com.skilledservices.platform.identity.domain.DuplicateContactException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AccountContactSnapshot(
    val email: String,
    val emailVerified: Boolean,
    val phone: String?,
    val phoneVerified: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val requiredContactVerified: Boolean
        get() = emailVerified || (phone != null && phoneVerified)
}

@Service
class IdentityContactService(
    private val accountStore: AccountStore,
    private val clock: Clock,
) {
    @Transactional
    fun updateCustomerContact(accountId: UUID, expectedEmail: String, requestedPhone: String?): AccountContactSnapshot {
        val account = accountStore.findById(accountId) ?: throw AccountNotFoundException()
        val email = expectedEmail.trim().lowercase()
        if (email != account.email) throw ContactEmailMismatchException()

        val phone = requestedPhone?.trim()?.takeIf { it.isNotEmpty() }
        if (phone == account.phone) return account.toContactSnapshot()
        if (phone != null && accountStore.existsByPhone(phone)) throw DuplicateContactException("phone number")

        return accountStore.save(
            account.copy(
                phone = phone,
                phoneVerified = false,
                updatedAt = clock.instant(),
            ),
        ).toContactSnapshot()
    }
}

internal fun com.skilledservices.platform.identity.domain.Account.toContactSnapshot() = AccountContactSnapshot(
    email = email,
    emailVerified = emailVerified,
    phone = phone,
    phoneVerified = phoneVerified,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
