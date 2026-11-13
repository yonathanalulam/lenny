package com.skilledservices.platform.profiles

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.application.AuthResult
import com.skilledservices.platform.identity.application.AuthService
import com.skilledservices.platform.identity.application.ClientContext
import com.skilledservices.platform.identity.application.RegistrationCommand
import com.skilledservices.platform.identity.domain.Account
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.identity.domain.AccountStore
import com.skilledservices.platform.identity.domain.AccessTokenPort
import com.skilledservices.platform.identity.domain.PasswordHashPort
import com.skilledservices.platform.profiles.application.CustomerEligibilityService
import com.skilledservices.platform.profiles.application.ProfileService
import com.skilledservices.platform.profiles.application.ProviderEligibilityService
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
import com.skilledservices.platform.profiles.domain.VerificationDecisionStore
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.post
import org.springframework.mock.web.MockMultipartFile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = [
    "platform.protected-storage.local-root=build/test-protected",
    "platform.protected-storage.max-file-size-bytes=32",
])
class ProfilesVerificationPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var accountStore: AccountStore
    @Autowired lateinit var passwordHashPort: PasswordHashPort
    @Autowired lateinit var accessTokenPort: AccessTokenPort
    @Autowired lateinit var profileService: ProfileService
    @Autowired lateinit var customerEligibility: CustomerEligibilityService
    @Autowired lateinit var providerEligibility: ProviderEligibilityService
    @Autowired lateinit var decisionStore: VerificationDecisionStore
    @Autowired lateinit var clock: Clock

    private val context = ClientContext("integration-test", "127.0.0.1")

    @Test
    fun `customer and provider onboarding verification and eligibility flow`() {
        val customer = registerCustomer()
        val otherCustomer = registerCustomer()
        val provider = registerProvider()
        val otherProvider = registerProvider()
        val admin = createAdminAccessToken()

        markEmailVerified(customer)
        markEmailVerified(otherCustomer)

        mockMvc.get("/api/v1/customers/me/onboarding") { bearer(customer.accessToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.role") { value("CUSTOMER") }
                jsonPath("$.accountStatus") { value("ACTIVE") }
                jsonPath("$.setupStatus") { value("IN_PROGRESS") }
                jsonPath("$.setupComplete") { value(false) }
                jsonPath("$.marketplaceEligible") { value(false) }
                jsonPath("$.contactVerification.emailVerified") { value(true) }
                jsonPath("$.contactVerification.phoneVerified") { value(false) }
                jsonPath("$.requirements.fullNameProvided") { value(false) }
                jsonPath("$.requirements.serviceAreaSelected") { value(false) }
                jsonPath("$.requirements.preferredLanguageSelected") { value(false) }
                jsonPath("$.requirements.requiredContactVerified") { value(true) }
                jsonPath("$.verificationStatus") { value("PENDING") }
                jsonPath("$.verificationSubmittedAt") { value(nullValue()) }
            }

        mockMvc.put("/api/v1/customers/me/profile") {
            bearer(customer.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = customerProfileJson(customer, "Sara Tadesse", "+2519${randomDigits()}", "en", "bole-medhanialem")
        }.andExpect {
            status { isOk() }
            jsonPath("$.setupComplete") { value(true) }
            jsonPath("$.fullName") { value("Sara Tadesse") }
            jsonPath("$.email") { value(customer.account.email) }
            jsonPath("$.emailVerified") { value(true) }
            jsonPath("$.phoneVerified") { value(false) }
            jsonPath("$.serviceAreaId") { value("bole-medhanialem") }
            jsonPath("$.serviceAreaName") { value("Bole / Medhanialem") }
            jsonPath("$.preferredLanguage") { value("en") }
            jsonPath("$.profilePhotoUrl") { value(nullValue()) }
        }

        mockMvc.get("/api/v1/customers/me/profile") { bearer(otherCustomer.accessToken) }
            .andExpect { status { isNotFound() } }
        mockMvc.put("/api/v1/customers/me/profile") {
            bearer(otherCustomer.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = customerProfileJson(otherCustomer, "Other Customer", null, "en", "kazanchis")
        }.andExpect { status { isOk() } }
        assertEquals("Bole / Medhanialem", profileService.getCustomer(customer.account.id).profile.serviceAreaName)

        mockMvc.get("/api/v1/customers/me/profile") { bearer(customer.accessToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.accountId") { value(customer.account.id.toString()) }
                jsonPath("$.fullName") { value("Sara Tadesse") }
                jsonPath("$.serviceAreaId") { value("bole-medhanialem") }
                jsonPath("$.createdAt") { exists() }
                jsonPath("$.updatedAt") { exists() }
            }

        mockMvc.put("/api/v1/customers/me/profile") {
            bearer(customer.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"fullName":"Sara Tadesse","email":"different@example.com","phone":null,"preferredLanguage":"en","serviceAreaId":"bole-medhanialem"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("identity.contact_email_mismatch") }
        }

        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = providerProfileJson("Plumbing and water-system specialist")
        }.andExpect {
            status { isOk() }
            jsonPath("$.setupComplete") { value(true) }
        }
        mockMvc.get("/api/v1/providers/me/profile") { bearer(otherProvider.accessToken) }
            .andExpect { status { isNotFound() } }
        assertEquals("Addis Ababa", profileService.getProvider(provider.account.id).serviceArea)

        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = providerProfileJson("Updated while marketplace verification remains pending")
        }.andExpect { status { isOk() } }
        assertFalse(providerEligibility.isMarketplaceEligible(provider.account.id))

        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(otherProvider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = providerProfileJson("Valid description for semantic validation", skill = " a ")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("PROFILE_INVALID") }
        }

        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"MONDAY","startTime":"08:00:00","endTime":"12:00:00"},{"dayOfWeek":"SATURDAY","startTime":"09:00:00","endTime":"15:00:00"}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.available") { value(true) }
            jsonPath("$.ranges", hasSize<Any>(2))
        }
        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"12:00:00"}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_AVAILABILITY_RANGE") }
        }
        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"WEDNESDAY","startTime":"07:30:00","endTime":"14:00:00"}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.ranges", hasSize<Any>(1))
        }
        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"MONDAY","startTime":"08:00:00","endTime":"12:00:00"},{"dayOfWeek":"MONDAY","startTime":"08:00:00","endTime":"12:00:00"}]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_AVAILABILITY_RANGE") }
        }
        mockMvc.get("/api/v1/providers/me/availability") { bearer(otherProvider.accessToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.available") { value(false) }
                jsonPath("$.ranges", hasSize<Any>(0))
            }
        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(otherProvider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"TUESDAY","startTime":"10:00:00","endTime":"11:00:00"}]}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/providers/me/availability") { bearer(provider.accessToken) }
            .andExpect { jsonPath("$.ranges", hasSize<Any>(1)) }

        submitInvalidEvidence(otherCustomer.accessToken, MediaType.TEXT_PLAIN_VALUE, "unsupported", 415, "UNSUPPORTED_UPLOAD_TYPE")
        submitInvalidEvidence(otherProvider.accessToken, MediaType.APPLICATION_PDF_VALUE, "x".repeat(33), 413, "UPLOAD_TOO_LARGE", provider = true)

        assertFalse(customerEligibility.canCreateMarketplaceRequest(customer.account.id))
        val customerEvidence = submitEvidence("/api/v1/customers/me/verification/submissions", customer.accessToken, "customer-id")
        val providerEvidence = submitEvidence("/api/v1/providers/me/verification/submissions", provider.accessToken, "provider-qualification")
        assertFalse(providerEligibility.isMarketplaceEligible(provider.account.id))

        mockMvc.post("/api/v1/admin/verifications/${customer.verifications.single().id}/decision") {
            bearer(customer.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"APPROVED","reason":"Self approval must not be possible."}"""
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/api/v1/admin/verifications?status=PENDING") { bearer(customer.accessToken) }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/admin/verifications?status=PENDING") { bearer(admin) }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalItems") { value(2) }
            }

        mockMvc.get("/api/v1/verifications/evidence/$customerEvidence/content") { bearer(otherCustomer.accessToken) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("FORBIDDEN_VERIFICATION_ACCESS") }
            }
        mockMvc.get("/api/v1/verifications/evidence/$customerEvidence/content") { bearer(admin) }
            .andExpect {
                status { isOk() }
                content { bytes("evidence-content".toByteArray()) }
            }

        val customerVerificationId = customer.verifications.single().id
        decide(admin, customerVerificationId, "REJECTED", "Required information is incomplete.")
        assertFalse(customerEligibility.canCreateMarketplaceRequest(customer.account.id))
        submitEvidence("/api/v1/customers/me/verification/submissions", customer.accessToken, "customer-id-resubmitted")
        decide(admin, customerVerificationId, "APPROVED", "Submitted verification information reviewed.")
        assertTrue(customerEligibility.canCreateMarketplaceRequest(customer.account.id))

        val providerVerificationId = provider.verifications.single().id
        decide(admin, providerVerificationId, "REJECTED", "Qualification evidence needs clarification.")
        assertFalse(providerEligibility.isMarketplaceEligible(provider.account.id))
        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(provider.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = providerProfileJson("Provider profile remains editable after rejection")
        }.andExpect { status { isOk() } }
        submitEvidence("/api/v1/providers/me/verification/submissions", provider.accessToken, "provider-resubmitted")
        decide(admin, providerVerificationId, "APPROVED", "Professional evidence reviewed.")
        assertTrue(providerEligibility.isMarketplaceEligible(provider.account.id))

        val providerDecisions = decisionStore.findByVerificationId(providerVerificationId)
        assertEquals(2, providerDecisions.size)
        assertNotNull(providerDecisions.first().reviewerAdminId)
        assertNotNull(providerDecisions.first().decidedAt)

        mockMvc.get("/api/v1/customers/me/onboarding") { bearer(customer.accessToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.verificationStatus") { value(MarketplaceVerificationStatus.APPROVED.name) }
                jsonPath("$.marketplaceEligible") { value(true) }
                jsonPath("$.setupComplete") { value(true) }
                jsonPath("$.setupStatus") { value("COMPLETE") }
                jsonPath("$.contactVerification.emailVerified") { value(true) }
                jsonPath("$.contactVerification.phoneVerified") { value(false) }
            }
        mockMvc.get("/api/v1/providers/me/onboarding") { bearer(provider.accessToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.verificationStatus") { value(MarketplaceVerificationStatus.APPROVED.name) }
                jsonPath("$.marketplaceEligible") { value(true) }
                jsonPath("$.setupComplete") { value(true) }
            }

        assertNotEquals(customerEvidence, providerEvidence)

        val openApi = mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/providers/me/availability']") { exists() }
            jsonPath("$.paths['/api/v1/admin/verifications/{verificationId}/decision']") { exists() }
        }.andReturn().response.contentAsString
        val snapshotDirectory = Path.of("build", "generated-openapi")
        Files.createDirectories(snapshotDirectory)
        Files.writeString(snapshotDirectory.resolve("openapi.json"), openApi)
    }

    private fun registerCustomer(): AuthResult = authService.registerCustomer(
        RegistrationCommand("customer-${UUID.randomUUID()}@example.com", null, "correct-horse-battery-staple"), context,
    )

    private fun registerProvider(): AuthResult = authService.registerServiceProvider(
        RegistrationCommand("provider-${UUID.randomUUID()}@example.com", null, "correct-horse-battery-staple"), context,
    )

    private fun markEmailVerified(result: AuthResult) {
        val account = accountStore.findById(result.account.id)!!
        accountStore.save(account.copy(emailVerified = true, updatedAt = clock.instant()))
    }

    private fun createAdminAccessToken(): String {
        val now = clock.instant()
        val admin = accountStore.save(
            Account(
                UUID.randomUUID(), "admin-${UUID.randomUUID()}@example.com", null,
                passwordHashPort.hash("admin-password-value"), AccountStatus.ACTIVE, setOf(AccountRole.ADMIN),
                emailVerified = true, phoneVerified = false, credentialVersion = 1, createdAt = now, updatedAt = now,
            ),
        )
        return accessTokenPort.issue(admin, UUID.randomUUID(), now).value
    }

    private fun submitEvidence(path: String, accessToken: String, identifier: String): UUID {
        val metadata = MockMultipartFile(
            "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
            """{"evidence":[{"identifier":"$identifier","type":"Supporting evidence"}]}""".toByteArray(),
        )
        val file = MockMultipartFile("files", "evidence.pdf", MediaType.APPLICATION_PDF_VALUE, "evidence-content".toByteArray())
        val response = mockMvc.multipart(path) {
            file(metadata)
            file(file)
            bearer(accessToken)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.submittedAt") { exists() }
        }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.submissions[0].evidence[0].id"))
    }

    private fun decide(adminToken: String, verificationId: UUID, decision: String, reason: String) {
        mockMvc.post("/api/v1/admin/verifications/$verificationId/decision") {
            bearer(adminToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"$decision","reason":"$reason"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value(decision) }
            jsonPath("$.reviewerAdminId") { exists() }
            jsonPath("$.reviewedAt") { exists() }
        }
    }

    private fun submitInvalidEvidence(
        token: String,
        contentTypeValue: String,
        contentValue: String,
        expectedStatus: Int,
        expectedCode: String,
        provider: Boolean = false,
    ) {
        val metadata = MockMultipartFile(
            "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
            """{"evidence":[{"identifier":"invalid-upload","type":"Supporting evidence"}]}""".toByteArray(),
        )
        val file = MockMultipartFile("files", "evidence.bin", contentTypeValue, contentValue.toByteArray())
        val path = if (provider) "/api/v1/providers/me/verification/submissions" else "/api/v1/customers/me/verification/submissions"
        mockMvc.multipart(path) {
            file(metadata)
            file(file)
            bearer(token)
        }.andExpect {
            status { isEqualTo(expectedStatus) }
            jsonPath("$.code") { value(expectedCode) }
        }
    }

    private fun providerProfileJson(description: String, skill: String = "Plumbing") =
        """{"professionalDescription":"$description","skills":["$skill"],"experienceYears":5,"serviceArea":"Addis Ababa","pricingSummary":"Pricing depends on scope","portfolioSummary":"Residential plumbing work"}"""

    private fun customerProfileJson(
        customer: AuthResult,
        fullName: String,
        phone: String?,
        preferredLanguage: String,
        serviceAreaId: String,
    ) = """{"fullName":"$fullName","email":"${customer.account.email}","phone":${phone?.let { "\"$it\"" } ?: "null"},"preferredLanguage":"$preferredLanguage","serviceAreaId":"$serviceAreaId"}"""

    private fun randomDigits() = UUID.randomUUID().toString().filter(Char::isDigit).padEnd(8, '7').take(8)

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
