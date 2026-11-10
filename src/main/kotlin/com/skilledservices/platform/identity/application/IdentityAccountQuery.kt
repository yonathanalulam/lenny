package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.identity.domain.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class IdentityAccountQuery(
    private val accountStore: AccountStore,
) {
    @Transactional(readOnly = true)
    fun hasRole(accountId: UUID, role: AccountRole): Boolean =
        accountStore.findById(accountId)?.roles?.contains(role) == true

    @Transactional(readOnly = true)
    fun accountStatus(accountId: UUID): AccountStatus? = accountStore.findById(accountId)?.status
}
