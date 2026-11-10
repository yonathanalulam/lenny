package com.skilledservices.platform.identity.infrastructure.security

import com.skilledservices.platform.identity.domain.AccessTokenPort
import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.IssuedAccessToken
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class JwtAccessTokenAdapter(
    private val jwtEncoder: JwtEncoder,
    private val properties: SecurityProperties,
) : AccessTokenPort {
    override fun issue(account: Account, sessionId: UUID, issuedAt: Instant): IssuedAccessToken {
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(account.id.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("sid", sessionId.toString())
            .claim("roles", account.roles.map { it.name }.sorted())
            .claim("accountStatus", account.status.name)
            .claim("credentialVersion", account.credentialVersion)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedAccessToken(token, expiresAt)
    }
}
