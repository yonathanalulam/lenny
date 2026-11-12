package com.skilledservices.platform.marketplace

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.application.*
import com.skilledservices.platform.identity.domain.*
import com.skilledservices.platform.marketplace.domain.ProviderCategoryStore
import com.skilledservices.platform.profiles.domain.*
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.*
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = [
    "platform.protected-storage.local-root=build/test-protected-marketplace",
    "platform.protected-storage.max-file-size-bytes=32",
])
class MarketplaceRequestsPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var accountStore: AccountStore
    @Autowired lateinit var passwordHash: PasswordHashPort
    @Autowired lateinit var tokens: AccessTokenPort
    @Autowired lateinit var verifications: MarketplaceVerificationStore
    @Autowired lateinit var providerCategories: ProviderCategoryStore
    @Autowired lateinit var clock: Clock

    private val client = ClientContext("milestone-3-test", "127.0.0.1")

    @Test
    fun `categories discovery invitations drafts attachments and privacy work end to end`() {
        val admin = createAdmin()
        val plumbing = createCategory(admin.token, "Plumbing", "የቧንቧ ሥራ")
        val repairs = createCategory(admin.token, "Plumbing Repairs", "የቧንቧ ጥገና", plumbing)
        val inactive = createCategory(admin.token, "Legacy Plumbing", "የቀድሞ ቧንቧ")

        val pendingCustomer = registerCustomer()
        mockMvc.post("/api/v1/admin/categories") {
            bearer(pendingCustomer.accessToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nameEn":"Electrical","nameAm":"ኤሌክትሪክ"}"""
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/categories") {
            bearer(admin.token)
            contentType = MediaType.APPLICATION_JSON
            content = """{"nameEn":"plumbing","nameAm":"ድግግሞሽ"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CATEGORY_DUPLICATE") }
        }

        val providerA = registerProvider()
        val providerBPending = registerProvider()
        val providerCRejected = registerProvider()
        val providerDInactive = registerProvider()
        val providerE = registerProvider()
        val providerHistorical = registerProvider()
        listOf(providerA, providerBPending, providerCRejected, providerDInactive, providerE, providerHistorical).forEachIndexed { index, provider ->
            completeProvider(provider, if (index == 4) "Kazanchis" else "Bole", 3 + index)
        }
        setVerification(providerA, MarketplaceVerificationStatus.APPROVED, admin.id)
        setVerification(providerCRejected, MarketplaceVerificationStatus.REJECTED, admin.id)
        setVerification(providerDInactive, MarketplaceVerificationStatus.APPROVED, admin.id)
        setVerification(providerE, MarketplaceVerificationStatus.APPROVED, admin.id)
        setVerification(providerHistorical, MarketplaceVerificationStatus.APPROVED, admin.id)
        assignCategories(providerA.accessToken, repairs)
        assignCategories(providerBPending.accessToken, repairs)
        assignCategories(providerCRejected.accessToken, repairs)
        assignCategories(providerDInactive.accessToken, repairs)
        assignCategories(providerE.accessToken, repairs)
        assignCategories(providerHistorical.accessToken, inactive)
        accountStore.save(providerDInactive.account.copy(status = AccountStatus.DEACTIVATED, updatedAt = clock.instant()))
        mockMvc.patch("/api/v1/admin/categories/$inactive") {
            bearer(admin.token); contentType = MediaType.APPLICATION_JSON; content = """{"active":false}"""
        }.andExpect { status { isOk() }; jsonPath("$.active") { value(false) } }
        assertTrue(providerCategories.findByProviderId(providerHistorical.account.id).any { it.categoryId == inactive })
        mockMvc.put("/api/v1/providers/me/categories") {
            bearer(providerBPending.accessToken); contentType = MediaType.APPLICATION_JSON; content = """{"categoryIds":["$inactive"]}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("CATEGORY_INACTIVE") } }

        mockMvc.get("/api/v1/categories").andExpect {
            status { isOk() }
            jsonPath("$[0].name.en") { value("Plumbing") }
            jsonPath("$[0].name.am") { value("የቧንቧ ሥራ") }
            jsonPath("$[0].subcategories[0].id") { value(repairs.toString()) }
            content { string(not(org.hamcrest.Matchers.containsString("Legacy Plumbing"))) }
        }

        mockMvc.get("/api/v1/providers?categoryId=$plumbing&serviceArea=Bole&available=true&minExperience=3&sort=experience,desc&page=0&size=1").andExpect {
            status { isOk() }
            jsonPath("$.totalItems") { value(1) }
            jsonPath("$.items[0].providerId") { value(providerA.account.id.toString()) }
            jsonPath("$.items[0].verificationApproved") { value(true) }
            jsonPath("$.items[0].email") { doesNotExist() }
            jsonPath("$.items[0].phone") { doesNotExist() }
        }
        mockMvc.get("/api/v1/providers?categoryId=$plumbing&page=0&size=1").andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(2) }; jsonPath("$.items", hasSize<Any>(1)); jsonPath("$.hasNext") { value(true) }
        }
        val nextMonday = LocalDate.now(clock).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        mockMvc.get("/api/v1/providers?requestedDate=$nextMonday&startTime=09:00:00&endTime=10:00:00").andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(2) }
        }
        mockMvc.get("/api/v1/providers?sort=rating,desc").andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(2) }
            jsonPath("$.items[0].ratingCount") { value(0) }
        }
        mockMvc.get("/api/v1/providers/${providerA.account.id}").andExpect {
            status { isOk() }; jsonPath("$.providerId") { value(providerA.account.id.toString()) }
            jsonPath("$.verificationEvidence") { doesNotExist() }
        }
        mockMvc.get("/api/v1/providers/${providerBPending.account.id}").andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/providers/compare?ids=${providerA.account.id}&ids=${providerE.account.id}").andExpect {
            status { isOk() }; jsonPath("$", hasSize<Any>(2)); jsonPath("$[0].phone") { doesNotExist() }
        }

        val approvedCustomer = registerCustomer()
        val rejectedCustomer = registerCustomer()
        val unrelatedCustomer = registerCustomer()
        setVerification(approvedCustomer, MarketplaceVerificationStatus.APPROVED, admin.id)
        setVerification(rejectedCustomer, MarketplaceVerificationStatus.REJECTED, admin.id)

        val requestJson = directRequestJson(repairs, providerA.account.id, nextMonday)
        assertRequestDenied(pendingCustomer.accessToken, requestJson)
        assertRequestDenied(rejectedCustomer.accessToken, requestJson)
        mockMvc.post("/api/v1/customers/me/requests") {
            bearer(approvedCustomer.accessToken); contentType = MediaType.APPLICATION_JSON
            content = directRequestJson(repairs, providerBPending.account.id, nextMonday)
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("PROVIDER_NOT_MARKETPLACE_ELIGIBLE") } }
        mockMvc.post("/api/v1/customers/me/requests") {
            bearer(approvedCustomer.accessToken); contentType = MediaType.APPLICATION_JSON
            content = directRequestJson(repairs, providerCRejected.account.id, nextMonday)
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("PROVIDER_NOT_MARKETPLACE_ELIGIBLE") } }

        val directResponse = mockMvc.post("/api/v1/customers/me/requests") {
            bearer(approvedCustomer.accessToken); contentType = MediaType.APPLICATION_JSON; content = requestJson
        }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("OPEN") }
            jsonPath("$.invitationStatus") { value("PENDING") }; jsonPath("$.postingFeeRequired") { value(false) }
        }.andReturn().response.contentAsString
        val directId = UUID.fromString(JsonPath.read(directResponse, "$.id"))
        val invitationsResponse = mockMvc.get("/api/v1/providers/me/invitations") { bearer(providerA.accessToken) }
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) } }
            .andReturn().response.contentAsString
        val invitationId = UUID.fromString(JsonPath.read<String>(invitationsResponse, "$.items[0].invitationId"))

        val attachmentId = uploadAttachment(approvedCustomer.accessToken, directId, "photo.png", MediaType.IMAGE_PNG_VALUE, "photo")
        uploadInvalid(approvedCustomer.accessToken, directId, MediaType.TEXT_PLAIN_VALUE, "text", 415, "UNSUPPORTED_UPLOAD_TYPE")
        uploadInvalid(approvedCustomer.accessToken, directId, MediaType.IMAGE_PNG_VALUE, "x".repeat(33), 413, "UPLOAD_TOO_LARGE")
        mockMvc.get("/api/v1/requests/attachments/$attachmentId/content") { bearer(unrelatedCustomer.accessToken) }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("FORBIDDEN_REQUEST_ATTACHMENT_ACCESS") } }
        mockMvc.get("/api/v1/requests/attachments/$attachmentId/content") { bearer(approvedCustomer.accessToken) }
            .andExpect { status { isOk() }; content { bytes("photo".toByteArray()) } }
        mockMvc.get("/api/v1/requests/attachments/$attachmentId/content") { bearer(providerA.accessToken) }
            .andExpect { status { isOk() }; content { bytes("photo".toByteArray()) } }
        mockMvc.get("/api/v1/requests/attachments/$attachmentId/content") { bearer(providerE.accessToken) }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/providers/me/invitations/$invitationId") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.generalArea") { value("Bole") }
            jsonPath("$.exactLocation") { doesNotExist() }; jsonPath("$.customerId") { doesNotExist() }; jsonPath("$.phone") { doesNotExist() }
            jsonPath("$.attachments[0].id") { value(attachmentId.toString()) }
            jsonPath("$.attachments[0].storageKey") { doesNotExist() }
        }
        mockMvc.get("/api/v1/providers/me/invitations/$invitationId") { bearer(providerE.accessToken) }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/customers/me/requests/$directId/cancel") { bearer(approvedCustomer.accessToken) }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("CANCELLED") }; jsonPath("$.invitationStatus") { value("WITHDRAWN") } }

        val jobResponse = mockMvc.post("/api/v1/customers/me/requests") {
            bearer(approvedCustomer.accessToken); contentType = MediaType.APPLICATION_JSON
            content = jobRequestJson(repairs, nextMonday)
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("DRAFT") }; jsonPath("$.published") { value(false) } }
            .andReturn().response.contentAsString
        val jobId = UUID.fromString(JsonPath.read(jobResponse, "$.id"))
        mockMvc.post("/api/v1/customers/me/requests/$jobId/request-publication") { bearer(approvedCustomer.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("AWAITING_POSTING_FEE") }
            jsonPath("$.postingFeeRequired") { value(true) }; jsonPath("$.published") { value(false) }
        }
        mockMvc.patch("/api/v1/customers/me/requests/$jobId") {
            bearer(approvedCustomer.accessToken); contentType = MediaType.APPLICATION_JSON; content = """{"description":"Updated material request description"}"""
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("DRAFT") } }
        mockMvc.get("/api/v1/customers/me/requests/$jobId") { bearer(unrelatedCustomer.accessToken) }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("REQUEST_NOT_OWNED") } }
        mockMvc.get("/api/v1/providers/me/invitations") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(1) }
        }
        mockMvc.get("/api/v1/jobs") { bearer(providerA.accessToken) }.andExpect { status { isNotFound() } }

        val openApi = mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/providers']") { exists() }
            jsonPath("$.paths['/api/v1/customers/me/requests']") { exists() }
            jsonPath("$.paths['/api/v1/providers/me/invitations/{invitationId}']") { exists() }
        }.andReturn().response.contentAsString
        val output = Path.of("build", "generated-openapi")
        Files.createDirectories(output)
        Files.writeString(output.resolve("openapi.json"), openApi)
    }

    private fun createCategory(token: String, en: String, am: String, parent: UUID? = null): UUID {
        val response = mockMvc.post("/api/v1/admin/categories") {
            bearer(token); contentType = MediaType.APPLICATION_JSON
            content = """{"parentId":${parent?.let { "\"$it\"" } ?: "null"},"nameEn":"$en","nameAm":"$am","active":true}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    private fun completeProvider(provider: AuthResult, area: String, experience: Int) {
        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(provider.accessToken); contentType = MediaType.APPLICATION_JSON
            content = """{"professionalDescription":"Experienced household plumbing professional","skills":["Plumbing"],"experienceYears":$experience,"serviceArea":"$area","pricingSummary":"From ETB 800 depending on scope","portfolioSummary":"Public project summary"}"""
        }.andExpect { status { isOk() } }
        mockMvc.put("/api/v1/providers/me/availability") {
            bearer(provider.accessToken); contentType = MediaType.APPLICATION_JSON
            content = """{"available":true,"ranges":[{"dayOfWeek":"MONDAY","startTime":"08:00:00","endTime":"12:00:00"}]}"""
        }.andExpect { status { isOk() } }
    }

    private fun assignCategories(token: String, vararg ids: UUID) {
        val json = ids.joinToString(",") { "\"$it\"" }
        mockMvc.put("/api/v1/providers/me/categories") { bearer(token); contentType = MediaType.APPLICATION_JSON; content = """{"categoryIds":[$json]}""" }
            .andExpect { status { isOk() } }
    }

    private fun assertRequestDenied(token: String, contentValue: String) {
        mockMvc.post("/api/v1/customers/me/requests") { bearer(token); contentType = MediaType.APPLICATION_JSON; content = contentValue }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_NOT_MARKETPLACE_ELIGIBLE") } }
    }

    private fun uploadAttachment(token: String, requestId: UUID, name: String, type: String, value: String): UUID {
        val response = mockMvc.multipart("/api/v1/customers/me/requests/$requestId/attachments") {
            file(MockMultipartFile("file", name, type, value.toByteArray())); bearer(token)
        }.andExpect { status { isOk() }; jsonPath("$.storageKey") { doesNotExist() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(response, "$.id"))
    }

    private fun uploadInvalid(token: String, requestId: UUID, type: String, value: String, statusCode: Int, code: String) {
        mockMvc.multipart("/api/v1/customers/me/requests/$requestId/attachments") {
            file(MockMultipartFile("file", "bad.bin", type, value.toByteArray())); bearer(token)
        }.andExpect { status { isEqualTo(statusCode) }; jsonPath("$.code") { value(code) } }
    }

    private fun directRequestJson(category: UUID, provider: UUID, date: LocalDate) =
        """{"type":"DIRECT_INVITATION","categoryId":"$category","providerId":"$provider","description":"Kitchen sink is leaking and needs repair","generalArea":"Bole","exactLocation":"Private building and apartment details","preferredDate":"$date","preferredStartTime":"09:00:00","preferredEndTime":"11:00:00","urgency":"NORMAL","budgetMin":800,"budgetMax":1200,"specialInstructions":"Bring plumbing tools"}"""
    private fun jobRequestJson(category: UUID, date: LocalDate) =
        """{"type":"JOB_POSTING","categoryId":"$category","description":"Bathroom pipe needs a complete inspection","generalArea":"Bole","exactLocation":"Private exact service address","preferredDate":"$date","budgetMin":1000,"budgetMax":2000}"""

    private fun registerCustomer() = authService.registerCustomer(RegistrationCommand("customer-${UUID.randomUUID()}@example.com", "+2519${UUID.randomUUID().toString().filter(Char::isDigit).padEnd(8, '1').take(8)}", "correct-horse-battery-staple"), client)
    private fun registerProvider() = authService.registerServiceProvider(RegistrationCommand("provider-${UUID.randomUUID()}@example.com", null, "correct-horse-battery-staple"), client)

    private data class Admin(val id: UUID, val token: String)
    private fun createAdmin(): Admin {
        val now = clock.instant()
        val account = accountStore.save(Account(UUID.randomUUID(), "admin-${UUID.randomUUID()}@example.com", null, passwordHash.hash("admin-password-value"), AccountStatus.ACTIVE, setOf(AccountRole.ADMIN), true, false, 1, now, now))
        return Admin(account.id, tokens.issue(account, UUID.randomUUID(), now).value)
    }

    private fun setVerification(result: AuthResult, status: MarketplaceVerificationStatus, reviewerId: UUID) {
        val current = result.verifications.single()
        val now = clock.instant()
        val decided = status != MarketplaceVerificationStatus.PENDING
        verifications.save(current.copy(status = status, submittedAt = now.takeIf { decided }, reviewedAt = now.takeIf { decided }, reviewerAdminId = reviewerId.takeIf { decided }, decisionNote = "Test administrative decision".takeIf { decided }, updatedAt = now))
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
