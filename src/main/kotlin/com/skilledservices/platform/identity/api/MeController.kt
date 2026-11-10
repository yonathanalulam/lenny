package com.skilledservices.platform.identity.api

import com.skilledservices.platform.identity.application.CurrentAccountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Current user")
class MeController(
    private val currentAccountService: CurrentAccountService,
) {
    @GetMapping
    @Operation(summary = "Get the authenticated account", security = [SecurityRequirement(name = "bearerAuth")])
    @ApiResponse(responseCode = "200", description = "Authenticated account")
    @ApiResponse(responseCode = "401", description = "Authentication is absent or no longer valid")
    fun me(@AuthenticationPrincipal jwt: Jwt): AccountResponse =
        currentAccountService.get(UUID.fromString(jwt.subject)).toResponse()
}
