package com.skilledservices.platform.identity

import com.skilledservices.platform.identity.application.AuthService
import com.skilledservices.platform.identity.application.ClientContext
import com.skilledservices.platform.identity.application.RegistrationCommand
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.InvalidRefreshTokenException
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
class AuthFlowPostgresIntegrationTest {
    @Autowired
    lateinit var authService: AuthService

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `customer registration refresh rotation and logout persist revocable sessions`() {
        val email = "customer-${UUID.randomUUID()}@example.com"
        val context = ClientContext("integration-test", "127.0.0.1")
        val registered = authService.registerCustomer(
            RegistrationCommand(email, "+251911234567", "correct-horse-battery-staple"),
            context,
        )

        assertEquals(setOf(AccountRole.CUSTOMER), registered.account.roles)
        assertFalse(registered.account.emailVerified)
        assertEquals(MarketplaceVerificationStatus.PENDING, registered.verifications.single().status)

        val sessionId = UUID.fromString(jwtDecoder.decode(registered.accessToken).getClaimAsString("sid"))
        val storedHash = jdbcTemplate.queryForObject(
            "select token_hash from refresh_sessions where id = ?",
            String::class.java,
            sessionId,
        )
        assertNotEquals(registered.refreshToken, storedHash)
        assertEquals(64, storedHash?.length)

        val rotated = authService.refresh(registered.refreshToken, context)
        assertNotEquals(registered.refreshToken, rotated.refreshToken)
        assertFailsWith<InvalidRefreshTokenException> {
            authService.refresh(registered.refreshToken, context)
        }

        val rotatedSessionId = UUID.fromString(jwtDecoder.decode(rotated.accessToken).getClaimAsString("sid"))
        authService.logout(rotatedSessionId, rotated.account.id)
        assertFailsWith<InvalidRefreshTokenException> {
            authService.refresh(rotated.refreshToken, context)
        }

        val revokedCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_sessions where account_id = ? and revoked_at is not null",
            Long::class.java,
            registered.account.id,
        )
        assertTrue((revokedCount ?: 0) >= 2)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
