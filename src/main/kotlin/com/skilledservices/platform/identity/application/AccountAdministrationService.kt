package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.*
import com.skilledservices.platform.shared.events.PlatformAuditCommand
import com.skilledservices.platform.shared.events.PlatformAuditPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AccountAdministrationService(
    private val accounts: AccountStore,
    private val sessions: RefreshSessionStore,
    private val audit: PlatformAuditPort,
    private val clock: Clock,
) {
    @Transactional
    fun changeStatus(adminId: UUID, accountId: UUID, status: AccountStatus): Account {
        if (adminId == accountId) throw AccountStatusChangeInvalidException("Administrators cannot change their own status through this operation")
        val account = accounts.findById(accountId) ?: throw AccountNotFoundException()
        if (account.status == status) return account
        val now = clock.instant()
        sessions.revokeAllForAccount(accountId, now)
        val updated = accounts.save(account.copy(status = status, credentialVersion = account.credentialVersion + 1, updatedAt = now))
        audit.record(PlatformAuditCommand(adminId, "ACCOUNT_STATUS_CHANGED", "ACCOUNT", accountId))
        return updated
    }
}
