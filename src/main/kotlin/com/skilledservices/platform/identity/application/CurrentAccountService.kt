package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.AccountNotFoundException
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.profiles.application.VerificationRegistrationPort
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CurrentAccountResult(
    val account: Account,
    val verifications: List<MarketplaceVerification>,
)

@Service
class CurrentAccountService(
    private val accountStore: AccountStore,
    private val verificationTrustService: VerificationRegistrationPort,
) {
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    fun get(accountId: UUID): CurrentAccountResult {
        val account = accountStore.findById(accountId) ?: throw AccountNotFoundException()
        return CurrentAccountResult(account, verificationTrustService.findByAccountId(accountId))
    }
}
