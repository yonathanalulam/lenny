package com.skilledservices.platform.identity.api

import com.skilledservices.platform.identity.application.AuthResult
import com.skilledservices.platform.identity.application.CurrentAccountResult
import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class RegistrationRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    @field:Schema(example = "customer@example.com")
    val email: String,

    @field:Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "must use E.164 format, for example +251911234567")
    @field:Schema(example = "+251911234567", nullable = true)
    val phone: String? = null,

    @field:Size(min = 12, max = 128)
    @field:Schema(format = "password", minLength = 12)
    val password: String,
)

data class LoginRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,

    @field:NotBlank
    @field:Size(max = 128)
    @field:Schema(format = "password")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    @field:Size(max = 256)
    @field:Schema(format = "password", description = "Opaque refresh token returned by login or registration")
    val refreshToken: String,
)

data class AuthResponse(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val user: AccountResponse,
)

data class AccountResponse(
    val id: UUID,
    val email: String,
    val phone: String?,
    val status: String,
    val roles: Set<String>,
    val contactVerification: ContactVerificationResponse,
    val marketplaceVerifications: List<MarketplaceVerificationResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ContactVerificationResponse(
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
)

data class MarketplaceVerificationResponse(
    val role: String,
    val status: String,
)

internal fun AuthResult.toResponse() = AuthResponse(
    accessToken = accessToken,
    accessTokenExpiresAt = accessTokenExpiresAt,
    refreshToken = refreshToken,
    refreshTokenExpiresAt = refreshTokenExpiresAt,
    user = account.toResponse(verifications),
)

internal fun CurrentAccountResult.toResponse() = account.toResponse(verifications)

private fun Account.toResponse(verifications: List<MarketplaceVerification>) = AccountResponse(
    id = id,
    email = email,
    phone = phone,
    status = status.name,
    roles = roles.map { it.name }.toSortedSet(),
    contactVerification = ContactVerificationResponse(
        emailVerified = emailVerified,
        phoneVerified = phoneVerified,
    ),
    marketplaceVerifications = verifications.map {
        MarketplaceVerificationResponse(role = it.subjectType.name, status = it.status.name)
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
