package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import java.time.Instant

data class RegistrationCommand(
    val email: String,
    val phone: String?,
    val password: String,
)

data class LoginCommand(
    val email: String,
    val password: String,
)

data class ClientContext(
    val userAgent: String?,
    val ipAddress: String?,
)

data class AuthResult(
    val account: Account,
    val verifications: List<MarketplaceVerification>,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)
