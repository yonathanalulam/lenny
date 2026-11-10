package com.skilledservices.platform.identity.infrastructure.security

import com.skilledservices.platform.identity.domain.SessionPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("platform.security")
data class SecurityProperties(
    val issuer: String,
    val accessTokenTtl: Duration,
    override val refreshTokenTtl: Duration,
    val jwtSecretBase64: String,
) : SessionPolicy
