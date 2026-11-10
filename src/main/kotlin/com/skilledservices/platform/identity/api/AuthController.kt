package com.skilledservices.platform.identity.api

import com.skilledservices.platform.identity.application.AuthService
import com.skilledservices.platform.identity.application.ClientContext
import com.skilledservices.platform.identity.application.LoginCommand
import com.skilledservices.platform.identity.application.RegistrationCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account registration and revocable token sessions")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/register/customers")
    @Operation(summary = "Register a customer account")
    @ApiResponse(responseCode = "201", description = "Customer account and session created")
    @ApiResponse(responseCode = "409", description = "Email or phone is already registered")
    fun registerCustomer(
        @Valid @RequestBody request: RegistrationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        val result = authService.registerCustomer(request.toCommand(), servletRequest.clientContext())
        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse())
    }

    @PostMapping("/register/service-providers")
    @Operation(summary = "Register a service-provider account")
    @ApiResponse(responseCode = "201", description = "Service-provider account and session created")
    @ApiResponse(responseCode = "409", description = "Email or phone is already registered")
    fun registerServiceProvider(
        @Valid @RequestBody request: RegistrationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AuthResponse> {
        val result = authService.registerServiceProvider(request.toCommand(), servletRequest.clientContext())
        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse())
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password")
    @ApiResponse(responseCode = "200", description = "Session created")
    @ApiResponse(responseCode = "401", description = "Credentials are invalid or the account is inactive")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
    ): AuthResponse = authService.login(
        LoginCommand(request.email, request.password),
        servletRequest.clientContext(),
    ).toResponse()

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh session and issue new tokens")
    @ApiResponse(responseCode = "200", description = "Session rotated")
    @ApiResponse(responseCode = "401", description = "Refresh token is invalid, expired, or revoked")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
        servletRequest: HttpServletRequest,
    ): AuthResponse = authService.refresh(request.refreshToken, servletRequest.clientContext()).toResponse()

    @PostMapping("/logout")
    @Operation(
        summary = "Revoke the current refresh session",
        security = [SecurityRequirement(name = "bearerAuth")],
    )
    @ApiResponse(responseCode = "204", description = "Current session revoked")
    fun logout(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        authService.logout(
            sessionId = UUID.fromString(jwt.getClaimAsString("sid")),
            accountId = UUID.fromString(jwt.subject),
        )
        return ResponseEntity.noContent().build()
    }

    private fun RegistrationRequest.toCommand() = RegistrationCommand(email, phone, password)

    private fun HttpServletRequest.clientContext() = ClientContext(
        userAgent = getHeader("User-Agent"),
        ipAddress = remoteAddr,
    )
}
