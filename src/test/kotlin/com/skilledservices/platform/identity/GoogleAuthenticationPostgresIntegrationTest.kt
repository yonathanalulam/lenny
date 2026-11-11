package com.skilledservices.platform.identity

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.application.GoogleIdentityVerifier
import com.skilledservices.platform.identity.application.VerifiedGoogleIdentity
import com.skilledservices.platform.identity.domain.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertNull

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(GoogleAuthenticationPostgresIntegrationTest.GoogleVerifierTestConfiguration::class)
class GoogleAuthenticationPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `new Google customer and provider receive platform sessions with pending verification`() {
        val customer = google("valid|customer-sub|google-customer@example.test", "CUSTOMER")
        val provider = google("valid|provider-sub|google-provider@example.test", "SERVICE_PROVIDER")

        mockMvc.get("/api/v1/me") { bearer(token(customer)) }.andExpect {
            status { isOk() }; jsonPath("$.roles[0]") { value("CUSTOMER") }
            jsonPath("$.contactVerification.emailVerified") { value(true) }
            jsonPath("$.contactVerification.phoneVerified") { value(false) }
            jsonPath("$.marketplaceVerifications[0].status") { value("PENDING") }
        }
        mockMvc.get("/api/v1/me") { bearer(token(provider)) }.andExpect {
            status { isOk() }; jsonPath("$.roles[0]") { value("SERVICE_PROVIDER") }
            jsonPath("$.marketplaceVerifications[0].status") { value("PENDING") }
        }
        val customerId = UUID.fromString(JsonPath.read(customer, "$.user.id"))
        assertNull(jdbc.queryForObject("select password_hash from accounts where id = ?", String::class.java, customerId))
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"google-customer@example.test","password":"some-password-value"}"""
        }.andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("identity.invalid_credentials") } }
    }

    @Test
    fun `Google registration rejects unsafe roles and verifier failures`() {
        googleFailure("valid|admin-sub|admin@example.test", "ADMIN", 400, "identity.google_registration_role_required")
        googleFailure("valid|missing-role|role@example.test", null, 400, "identity.google_registration_role_required")
        googleFailure("unverified", "CUSTOMER", 401, "identity.google_email_not_verified")
        googleFailure("audience", "CUSTOMER", 401, "identity.google_audience_invalid")
        googleFailure("invalid", "CUSTOMER", 401, "identity.google_token_invalid")
    }

    @Test
    fun `email collision requires explicit authenticated linking and linked login works`() {
        val native = nativeRegister("link-user@example.test")
        googleFailure("valid|link-sub|link-user@example.test", null, 409, "identity.google_account_link_required")
        mockMvc.post("/api/v1/me/auth-identities/google") {
            bearer(token(native)); contentType = MediaType.APPLICATION_JSON
            content = """{"idToken":"valid|link-sub|link-user@example.test"}"""
        }.andExpect { status { isOk() }; jsonPath("$.provider") { value("GOOGLE") } }
        val linkedLogin = google("valid|link-sub|link-user@example.test", null)
        mockMvc.get("/api/v1/me") { bearer(token(linkedLogin)) }.andExpect {
            status { isOk() }; jsonPath("$.email") { value("link-user@example.test") }
        }
        mockMvc.post("/api/v1/me/auth-identities/google") {
            bearer(token(native)); contentType = MediaType.APPLICATION_JSON
            content = """{"idToken":"valid|link-sub|link-user@example.test"}"""
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("identity.google_identity_already_linked") } }
    }

    @Test
    fun `Google sessions obey account status refresh rotation and logout revocation`() {
        val auth = google("valid|session-sub|session-google@example.test", "CUSTOMER")
        val refresh = JsonPath.read<String>(auth, "$.refreshToken")
        val rotated = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON; content = """{"refreshToken":"$refresh"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON; content = """{"refreshToken":"$refresh"}"""
        }.andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/v1/auth/logout") { bearer(token(rotated)) }.andExpect { status { isNoContent() } }
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"${JsonPath.read<String>(rotated, "$.refreshToken")}"}"""
        }.andExpect { status { isUnauthorized() } }

        jdbc.update("update accounts set status = 'SUSPENDED' where email = ?", "session-google@example.test")
        googleFailure("valid|session-sub|session-google@example.test", null, 401, "identity.account_inactive")
    }

    private fun google(idToken: String, role: String?): String {
        val roleField = role?.let { ",\"requestedRole\":\"$it\"" }.orEmpty()
        return mockMvc.post("/api/v1/auth/google") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"idToken":"$idToken"$roleField}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
    }

    private fun googleFailure(idToken: String, role: String?, status: Int, code: String) {
        val roleField = role?.let { ",\"requestedRole\":\"$it\"" }.orEmpty()
        mockMvc.post("/api/v1/auth/google") {
            contentType = MediaType.APPLICATION_JSON; content = """{"idToken":"$idToken"$roleField}"""
        }.andExpect { status { isEqualTo(status) }; jsonPath("$.code") { value(code) } }
    }

    private fun nativeRegister(email: String) = mockMvc.post("/api/v1/auth/register/customers") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"correct-horse-battery-staple"}"""
    }.andExpect { status { isCreated() } }.andReturn().response.contentAsString

    private fun token(json: String): String = JsonPath.read(json, "$.accessToken")
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) = header("Authorization", "Bearer $token")

    @TestConfiguration
    class GoogleVerifierTestConfiguration {
        @Bean @Primary
        fun testGoogleVerifier() = GoogleIdentityVerifier { token ->
            when (token) {
                "invalid" -> throw GoogleTokenInvalidException()
                "unverified" -> throw GoogleEmailNotVerifiedException()
                "audience" -> throw GoogleAudienceInvalidException()
                else -> {
                    val parts = token.split('|')
                    if (parts.size != 3 || parts[0] != "valid") throw GoogleTokenInvalidException()
                    VerifiedGoogleIdentity(parts[1], parts[2])
                }
            }
        }
    }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
