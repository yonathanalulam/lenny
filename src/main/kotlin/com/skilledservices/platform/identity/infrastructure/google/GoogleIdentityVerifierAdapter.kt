package com.skilledservices.platform.identity.infrastructure.google

import com.skilledservices.platform.identity.application.GoogleIdentityVerifier
import com.skilledservices.platform.identity.application.VerifiedGoogleIdentity
import com.skilledservices.platform.identity.domain.*
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.oauth2.core.*
import org.springframework.security.oauth2.jwt.*
import org.springframework.stereotype.Component

@ConfigurationProperties("platform.google-auth")
data class GoogleAuthProperties(
    val allowedClientIds: Set<String> = emptySet(),
)

@Component
class GoogleIdentityVerifierAdapter(private val properties: GoogleAuthProperties) : GoogleIdentityVerifier {
    private val decoder: JwtDecoder by lazy {
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build().also { decoder ->
            val issuer = OAuth2TokenValidator<Jwt> { jwt ->
                if (jwt.issuer?.toString() in GOOGLE_ISSUERS) OAuth2TokenValidatorResult.success()
                else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Google issuer is invalid", null))
            }
            val audience = OAuth2TokenValidator<Jwt> { jwt ->
                if (jwt.audience.orEmpty().any(properties.allowedClientIds::contains)) OAuth2TokenValidatorResult.success()
                else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Google audience is invalid", null))
            }
            decoder.setJwtValidator(DelegatingOAuth2TokenValidator(JwtTimestampValidator(), issuer, audience))
        }
    }

    override fun verifyIdToken(idToken: String): VerifiedGoogleIdentity {
        if (properties.allowedClientIds.isEmpty()) throw GoogleAuthUnavailableException()
        val jwt = try {
            decoder.decode(idToken)
        } catch (exception: JwtValidationException) {
            val descriptions = exception.errors.joinToString(" ") { it.description.orEmpty() }.lowercase()
            when {
                "expired" in descriptions -> throw GoogleTokenExpiredException()
                "audience" in descriptions -> throw GoogleAudienceInvalidException()
                else -> throw GoogleTokenInvalidException()
            }
        } catch (_: JwtException) {
            throw GoogleTokenInvalidException()
        }
        if (jwt.getClaim<Boolean>("email_verified") != true) throw GoogleEmailNotVerifiedException()
        val email = jwt.getClaimAsString("email")?.trim()?.lowercase().orEmpty()
        val subject = jwt.subject.orEmpty()
        if (email.isBlank() || subject.isBlank()) throw GoogleTokenInvalidException()
        return VerifiedGoogleIdentity(subject, email)
    }

    companion object {
        private val GOOGLE_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
