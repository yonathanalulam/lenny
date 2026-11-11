package com.skilledservices.platform.identity.application

import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.profiles.domain.MarketplaceVerification
import java.time.Instant
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.ExternalIdentity

data class RegistrationCommand(
    val email: String,
    val phone: String?,
    val password: String,
)

data class LoginCommand(
    val email: String,
    val password: String,
)

data class GoogleAuthCommand(
    val idToken: String,
    val requestedRole: AccountRole?,
    val phone: String?,
)

data class GoogleLinkResult(val identity: ExternalIdentity)

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
