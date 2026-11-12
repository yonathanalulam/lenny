package com.skilledservices.platform.administration.api

import com.skilledservices.platform.administration.application.AdministrationReportingService
import com.skilledservices.platform.identity.application.AccountAdministrationService
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.validation.annotation.Validated
import java.time.Instant
import java.util.UUID

data class ChangeAccountStatusRequest(val status: AccountStatus)
data class AdminAccountResponse(val id: UUID, val status: AccountStatus, val roles: Set<AccountRole>, val updatedAt: Instant)

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Validated
class AdministrationController(
    private val reporting: AdministrationReportingService,
    private val accounts: AccountAdministrationService,
) {
    @GetMapping("/dashboard") fun dashboard() = reporting.dashboard()

    @GetMapping("/audit-events")
    fun audit(@RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) @Size(max = 100) eventType: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int) =
        reporting.audit(actorId, eventType, page, size)

    @PostMapping("/accounts/{accountId}/status")
    fun status(@AuthenticationPrincipal jwt: Jwt, @PathVariable accountId: UUID,
        @RequestBody request: ChangeAccountStatusRequest): AdminAccountResponse {
        val account = accounts.changeStatus(UUID.fromString(jwt.subject), accountId, request.status)
        return AdminAccountResponse(account.id, account.status, account.roles, account.updatedAt)
    }
}
