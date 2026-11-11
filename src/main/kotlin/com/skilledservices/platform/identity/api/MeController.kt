package com.skilledservices.platform.identity.api

import com.skilledservices.platform.identity.application.CurrentAccountService
import com.skilledservices.platform.identity.application.AuthService
import jakarta.validation.Valid
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Current user")
class MeController(
    private val currentAccountService: CurrentAccountService,
    private val authService: AuthService,
) {
    @GetMapping
    @Operation(summary = "Get the authenticated account", security = [SecurityRequirement(name = "bearerAuth")])
    @ApiResponse(responseCode = "200", description = "Authenticated account")
    @ApiResponse(responseCode = "401", description = "Authentication is absent or no longer valid")
    fun me(@AuthenticationPrincipal jwt: Jwt): AccountResponse =
        currentAccountService.get(UUID.fromString(jwt.subject)).toResponse()

    @PostMapping("/auth-identities/google")
    @Operation(summary = "Link Google to the authenticated account", security = [SecurityRequirement(name = "bearerAuth")])
    fun linkGoogle(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: GoogleLinkRequest,
    ): ExternalIdentityResponse = authService.linkGoogle(UUID.fromString(jwt.subject), request.idToken).identity.toResponse()
}
