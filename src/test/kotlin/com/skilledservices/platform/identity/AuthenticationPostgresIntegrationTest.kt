package com.skilledservices.platform.identity

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.domain.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountStore
    @Autowired lateinit var passwordHash: PasswordHashPort
    @Autowired lateinit var accessTokens: AccessTokenPort
    @Autowired lateinit var jwtEncoder: JwtEncoder
    @Autowired lateinit var clock: Clock

    @Test
    fun `customer registration persists only hashed credentials and me uses JWT identity`() {
        val email = "customer-${UUID.randomUUID()}@example.com"
        val response = register("customers", email, null, extra = ",\"roles\":[\"ADMIN\"]")
        val accountId = UUID.fromString(JsonPath.read(response, "$.user.id"))
        val accessToken = JsonPath.read<String>(response, "$.accessToken")
        val stored = jdbc.queryForMap("select password_hash, status, phone from accounts where id = ?", accountId)
        assertNotEquals(PASSWORD, stored["password_hash"])
        assertTrue(passwordHash.matches(PASSWORD, stored["password_hash"] as String))
        assertTrue(jdbc.queryForObject("select count(*) from account_roles where account_id = ? and role = 'CUSTOMER'", Long::class.java, accountId) == 1L)
        assertTrue(jdbc.queryForObject("select count(*) from account_roles where account_id = ? and role = 'ADMIN'", Long::class.java, accountId) == 0L)
        assertTrue(stored["phone"] == null)

        mockMvc.get("/api/v1/me") { bearer(accessToken) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(accountId.toString()) }
            jsonPath("$.email") { value(email) }
            jsonPath("$.roles[0]") { value("CUSTOMER") }
            jsonPath("$.passwordHash") { doesNotExist() }
            jsonPath("$.refreshToken") { doesNotExist() }
        }
        mockMvc.get("/api/v1/me").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("security.authentication_required") } }
        mockMvc.get("/api/v1/me") { header("Authorization", "Bearer not-a-jwt") }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/me") { bearer(expiredToken(accountId)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `provider registration and both native login paths work without exposing credentials`() {
        val customerEmail = "login-customer-${UUID.randomUUID()}@example.com"
        val providerEmail = "login-provider-${UUID.randomUUID()}@example.com"
        register("customers", customerEmail, null)
        val provider = register("service-providers", providerEmail, null)
        val providerId = UUID.fromString(JsonPath.read(provider, "$.user.id"))
        assertTrue(jdbc.queryForObject("select count(*) from account_roles where account_id = ? and role = 'SERVICE_PROVIDER'", Long::class.java, providerId) == 1L)
        assertFalse((jdbc.queryForObject("select password_hash from accounts where id = ?", String::class.java, providerId) ?: "") == PASSWORD)

        login(customerEmail, PASSWORD, 200)
        val providerLogin = login(providerEmail, PASSWORD, 200)
        val providerToken = JsonPath.read<String>(providerLogin, "$.accessToken")
        mockMvc.get("/api/v1/me") { bearer(providerToken) }.andExpect { status { isOk() }; jsonPath("$.roles[0]") { value("SERVICE_PROVIDER") } }
        login(providerEmail, "wrong-password-value", 401)
        login("missing-${UUID.randomUUID()}@example.com", PASSWORD, 401)
    }

    @Test
    fun `duplicate and malformed registration use stable problem details`() {
        val email = "duplicate-${UUID.randomUUID()}@example.com"
        val phone = "+2519${randomDigits()}"
        register("customers", email, null)
        mockMvc.post("/api/v1/auth/register/customers") {
            contentType = MediaType.APPLICATION_JSON
            content = customerRegistrationJson(email)
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("identity.contact_already_registered") } }
        register("service-providers", "provider-${UUID.randomUUID()}@example.com", phone)
        mockMvc.post("/api/v1/auth/register/service-providers") {
            contentType = MediaType.APPLICATION_JSON
            content = registrationJson("other-${UUID.randomUUID()}@example.com", phone)
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("identity.contact_already_registered") } }
        mockMvc.post("/api/v1/auth/register/customers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"not-email","password":"short"}"""
        }.andExpect {
            status { isBadRequest() }; content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("request.validation_failed") }
        }

        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/auth/register/customers'].post.requestBody.content['application/json'].schema['${'$'}ref']") {
                value("#/components/schemas/CustomerRegistrationRequest")
            }
            jsonPath("$.components.schemas.CustomerRegistrationRequest.properties.phone") { doesNotExist() }
            jsonPath("$.components.schemas.CustomerRegistrationRequest.properties.email") { exists() }
            jsonPath("$.components.schemas.CustomerRegistrationRequest.properties.password") { exists() }
        }
    }

    @Test
    fun `suspended and deactivated accounts cannot login or keep using access tokens`() {
        val suspendedEmail = "suspended-${UUID.randomUUID()}@example.com"
        val registered = register("customers", suspendedEmail, null)
        val accountId = UUID.fromString(JsonPath.read(registered, "$.user.id"))
        val token = JsonPath.read<String>(registered, "$.accessToken")
        val account = accounts.findById(accountId)!!
        accounts.save(account.copy(status = AccountStatus.SUSPENDED, updatedAt = clock.instant()))
        login(suspendedEmail, PASSWORD, 401, "identity.account_inactive")
        mockMvc.get("/api/v1/me") { bearer(token) }.andExpect { status { isUnauthorized() } }

        val deactivatedEmail = "deactivated-${UUID.randomUUID()}@example.com"
        val deactivated = register("service-providers", deactivatedEmail, null)
        val deactivatedId = UUID.fromString(JsonPath.read(deactivated, "$.user.id"))
        accounts.save(accounts.findById(deactivatedId)!!.copy(status = AccountStatus.DEACTIVATED, updatedAt = clock.instant()))
        login(deactivatedEmail, PASSWORD, 401, "identity.account_inactive")
    }

    @Test
    fun `refresh rotates once and logout revokes the current server session`() {
        val email = "refresh-${UUID.randomUUID()}@example.com"
        val registered = register("customers", email, null)
        val firstRefresh = JsonPath.read<String>(registered, "$.refreshToken")
        val rotated = refresh(firstRefresh, 200)
        val rotatedRefresh = JsonPath.read<String>(rotated, "$.refreshToken")
        assertNotEquals(firstRefresh, rotatedRefresh)
        refresh(firstRefresh, 401)
        val accessToken = JsonPath.read<String>(rotated, "$.accessToken")
        mockMvc.post("/api/v1/auth/logout") { bearer(accessToken) }.andExpect { status { isNoContent() } }
        refresh(rotatedRefresh, 401)
    }

    @Test
    fun `role boundaries deny cross-role and admin access`() {
        val customer = register("customers", "role-customer-${UUID.randomUUID()}@example.com", null)
        val provider = register("service-providers", "role-provider-${UUID.randomUUID()}@example.com", null)
        val customerToken = JsonPath.read<String>(customer, "$.accessToken")
        val providerToken = JsonPath.read<String>(provider, "$.accessToken")
        val admin = createAdminToken()

        mockMvc.get("/api/v1/providers/me/profile") { bearer(customerToken) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/customers/me/profile") { bearer(providerToken) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/admin/verifications") { bearer(customerToken) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/customers/me/profile") { bearer(admin) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/providers/me/profile").andExpect { status { isUnauthorized() } }
    }

    private fun register(kind: String, email: String, phone: String?, extra: String = ""): String =
        mockMvc.post("/api/v1/auth/register/$kind") {
            contentType = MediaType.APPLICATION_JSON
            content = if (kind == "customers") customerRegistrationJson(email, extra) else registrationJson(email, phone, extra)
        }.andExpect { status { isCreated() }; jsonPath("$.tokenType") { value("Bearer") } }
            .andReturn().response.contentAsString

    private fun login(email: String, password: String, expected: Int, code: String? = null): String =
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isEqualTo(expected) }; if (code != null) jsonPath("$.code") { value(code) } }
            .andReturn().response.contentAsString

    private fun refresh(token: String, expected: Int): String = mockMvc.post("/api/v1/auth/refresh") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"refreshToken":"$token"}"""
    }.andExpect { status { isEqualTo(expected) } }.andReturn().response.contentAsString

    private fun registrationJson(email: String, phone: String?, extra: String = "") =
        """{"email":"$email","phone":${phone?.let { "\"$it\"" } ?: "null"},"password":"$PASSWORD"$extra}"""

    private fun customerRegistrationJson(email: String, extra: String = "") =
        """{"email":"$email","password":"$PASSWORD"$extra}"""

    private fun createAdminToken(): String {
        val now = clock.instant()
        val account = accounts.save(Account(UUID.randomUUID(), "admin-${UUID.randomUUID()}@example.com", null,
            passwordHash.hash(PASSWORD), AccountStatus.ACTIVE, setOf(AccountRole.ADMIN), true, false, 1, now, now))
        return accessTokens.issue(account, UUID.randomUUID(), now).value
    }

    private fun expiredToken(accountId: UUID): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder().issuer("skilled-services-platform").subject(accountId.toString())
            .issuedAt(now.minusSeconds(120)).expiresAt(now.minusSeconds(60)).claim("sid", UUID.randomUUID().toString())
            .claim("roles", listOf("CUSTOMER")).claim("credentialVersion", 1).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    private fun randomDigits() = UUID.randomUUID().toString().filter(Char::isDigit).padEnd(8, '7').take(8)
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        private const val PASSWORD = "correct-horse-battery-staple"
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
