package com.skilledservices.platform.requests

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.application.*
import com.skilledservices.platform.identity.domain.*
import com.skilledservices.platform.profiles.domain.*
import com.skilledservices.platform.requests.domain.*
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.*
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = [
    "platform.posting-fee.provider=local-test",
    "platform.posting-fee.amount=175.50",
    "platform.posting-fee.currency=ETB",
    "platform.protected-storage.local-root=build/test-protected-m4",
])
class PostingFeeProposalsPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var accountStore: AccountStore
    @Autowired lateinit var passwordHash: PasswordHashPort
    @Autowired lateinit var tokens: AccessTokenPort
    @Autowired lateinit var verificationStore: MarketplaceVerificationStore
    @Autowired lateinit var requestStore: ServiceRequestStore
    @Autowired lateinit var paymentStore: PostingFeeTransactionStore
    @Autowired lateinit var proposalStore: ProposalStore
    @Autowired lateinit var paymentService: com.skilledservices.platform.requests.application.PostingFeePaymentService
    @Autowired lateinit var clock: Clock

    private val context = ClientContext("milestone-4-test", "127.0.0.1")
    private val serviceDate: LocalDate get() = LocalDate.now(clock).plusDays(5)

    @Test
    fun `verified payment publishes once and invitations proposals remain booking safe`() {
        val admin = createAdmin()
        val plumbing = createCategory(admin.token, "Plumbing", "የቧንቧ ሥራ")
        val electrical = createCategory(admin.token, "Electrical", "ኤሌክትሪክ")
        val customer = registerCustomer()
        val otherCustomer = registerCustomer()
        val pendingCustomer = registerCustomer()
        approve(customer, admin.id)
        approve(otherCustomer, admin.id)

        val providerA = registerProvider()
        val providerB = registerProvider()
        val pendingProvider = registerProvider()
        val rejectedProvider = registerProvider()
        val wrongCategoryProvider = registerProvider()
        val inactiveProvider = registerProvider()
        listOf(providerA, providerB, pendingProvider, rejectedProvider, wrongCategoryProvider, inactiveProvider).forEach {
            completeProvider(it, "Bole")
        }
        approve(providerA, admin.id)
        approve(providerB, admin.id)
        reject(rejectedProvider, admin.id)
        approve(wrongCategoryProvider, admin.id)
        approve(inactiveProvider, admin.id)
        assign(providerA, plumbing); assign(providerB, plumbing); assign(pendingProvider, plumbing); assign(rejectedProvider, plumbing)
        assign(wrongCategoryProvider, electrical); assign(inactiveProvider, plumbing)

        val jobId = createJob(customer.accessToken, plumbing)
        val attachmentId = uploadAttachment(customer.accessToken, jobId)
        mockMvc.post("/api/v1/customers/me/requests/$jobId/request-publication") { bearer(customer.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("AWAITING_POSTING_FEE") }
        }
        mockMvc.get("/api/v1/providers/me/jobs") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(0) }
        }

        setStatus(customer, MarketplaceVerificationStatus.PENDING, admin.id)
        mockMvc.post("/api/v1/customers/me/requests/$jobId/posting-fee/payments") { bearer(customer.accessToken) }.andExpect {
            status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_NOT_MARKETPLACE_ELIGIBLE") }
        }
        approve(customer, admin.id)

        val directId = createDirect(customer.accessToken, plumbing, providerA.account.id)
        assertTrue(paymentStore.findByRequestId(directId).isEmpty())
        mockMvc.post("/api/v1/customers/me/requests/$directId/posting-fee/payments") { bearer(customer.accessToken) }.andExpect {
            status { isBadRequest() }; jsonPath("$.code") { value("POSTING_FEE_NOT_REQUIRED") }
        }
        mockMvc.post("/api/v1/customers/me/requests/$jobId/posting-fee/payments") { bearer(otherCustomer.accessToken) }.andExpect {
            status { isForbidden() }; jsonPath("$.code") { value("REQUEST_NOT_OWNED") }
        }

        var payment = initiatePayment(customer.accessToken, jobId)
        assertEquals(BigDecimal("175.50"), payment.amount)
        mockMvc.get("/api/v1/customers/me/requests/$jobId/posting-fee") { bearer(customer.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.transactionStatus") { value("PENDING") }; jsonPath("$.published") { value(false) }
        }

        callback(callbackBody("invalid-signature", payment.id, payment.providerReferenceId!!, "SUCCESS", "175.50", "ETB"), "bad", 401, "PAYMENT_CALLBACK_INVALID")
        callback(callbackBody("unknown-event", UUID.randomUUID(), "unknown", "SUCCESS", "175.50", "ETB"), null, 400, "PAYMENT_VERIFICATION_FAILED")
        callback(callbackBody("wrong-amount", payment.id, payment.providerReferenceId, "SUCCESS", "175.51", "ETB"), null, 400, "PAYMENT_AMOUNT_MISMATCH")
        assertEquals(ServiceRequestStatus.AWAITING_POSTING_FEE, requestStore.findById(jobId)!!.status)

        payment = initiatePayment(customer.accessToken, jobId)
        callback(callbackBody("wrong-currency", payment.id, payment.providerReferenceId!!, "SUCCESS", "175.50", "USD"), null, 400, "PAYMENT_AMOUNT_MISMATCH")
        payment = initiatePayment(customer.accessToken, jobId)
        callback(callbackBody("failed-event", payment.id, payment.providerReferenceId!!, "FAILED", "175.50", "ETB", "DECLINED"), null, 200, null)
        assertEquals(ServiceRequestStatus.AWAITING_POSTING_FEE, requestStore.findById(jobId)!!.status)

        payment = initiatePayment(customer.accessToken, jobId)
        val success = callbackBody("success-event", payment.id, payment.providerReferenceId!!, "SUCCESS", "175.50", "ETB")
        callback(success, null, 200, null)
        callback(success, null, 200, null)
        assertEquals(ServiceRequestStatus.OPEN, requestStore.findById(jobId)!!.status)
        assertEquals(1, paymentStore.findByRequestId(jobId).count { it.status == PostingFeeStatus.SUCCESSFUL })
        assertNotNull(paymentStore.findSuccessfulByRequestId(jobId)?.verifiedAt)
        mockMvc.post("/api/v1/customers/me/requests/$jobId/posting-fee/payments") { bearer(customer.accessToken) }.andExpect {
            status { isConflict() }; jsonPath("$.code") { value("POSTING_FEE_ALREADY_PAID") }
        }
        val oldFailed = paymentStore.findByRequestId(jobId).first { it.status == PostingFeeStatus.FAILED }
        callback(callbackBody("late-success", oldFailed.id, oldFailed.providerReferenceId!!, "SUCCESS", "175.50", "ETB"), null, 200, null)
        assertEquals(1, paymentStore.findByRequestId(jobId).count { it.status == PostingFeeStatus.SUCCESSFUL })

        mockMvc.get("/api/v1/providers/me/jobs") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.totalItems") { value(1) }; jsonPath("$.items[0].requestId") { value(jobId.toString()) }
            jsonPath("$.items[0].exactLocation") { doesNotExist() }; jsonPath("$.items[0].phone") { doesNotExist() }; jsonPath("$.items[0].email") { doesNotExist() }
        }
        mockMvc.get("/api/v1/providers/me/jobs/$jobId") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.attachments[0].id") { value(attachmentId.toString()) }; jsonPath("$.exactLocation") { doesNotExist() }
        }
        mockMvc.get("/api/v1/requests/attachments/$attachmentId/content") { bearer(providerA.accessToken) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/providers/me/jobs") { bearer(pendingProvider.accessToken) }.andExpect { status { isConflict() } }
        mockMvc.get("/api/v1/providers/me/jobs") { bearer(rejectedProvider.accessToken) }.andExpect { status { isConflict() } }
        mockMvc.get("/api/v1/providers/me/jobs") { bearer(wrongCategoryProvider.accessToken) }.andExpect { status { isOk() }; jsonPath("$.totalItems") { value(0) } }
        accountStore.save(inactiveProvider.account.copy(status = AccountStatus.DEACTIVATED, updatedAt = clock.instant()))
        mockMvc.get("/api/v1/providers/me/jobs") { bearer(inactiveProvider.accessToken) }.andExpect { status { isUnauthorized() } }

        val jobProposal = submitProposal(providerA.accessToken, jobId, "900.00")
        mockMvc.post("/api/v1/providers/me/requests/$jobId/proposals") {
            bearer(pendingProvider.accessToken); contentType = MediaType.APPLICATION_JSON; content = proposalJson("880.00")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("PROVIDER_NOT_MARKETPLACE_ELIGIBLE") } }
        mockMvc.post("/api/v1/providers/me/requests/$jobId/proposals") {
            bearer(providerA.accessToken); contentType = MediaType.APPLICATION_JSON; content = proposalJson("900.00")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("PROPOSAL_ALREADY_EXISTS") } }
        mockMvc.get("/api/v1/customers/me/requests/$jobId/proposals") { bearer(customer.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$", hasSize<Any>(1)); jsonPath("$[0].provider.email") { doesNotExist() }
        }
        mockMvc.get("/api/v1/customers/me/proposals/$jobProposal") { bearer(otherCustomer.accessToken) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/customers/me/proposals/$jobProposal/reject") { bearer(customer.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("REJECTED") }
        }
        mockMvc.post("/api/v1/customers/me/proposals/$jobProposal/reject") { bearer(customer.accessToken) }.andExpect { status { isOk() } }
        mockMvc.post("/api/v1/providers/me/proposals/$jobProposal/withdraw") { bearer(providerA.accessToken) }.andExpect {
            status { isConflict() }; jsonPath("$.code") { value("PROPOSAL_NOT_PENDING") }
        }
        assertEquals(ServiceRequestStatus.OPEN, requestStore.findById(jobId)!!.status)

        val withdrawProposal = submitProposal(providerB.accessToken, jobId, "950.00")
        mockMvc.post("/api/v1/providers/me/proposals/$withdrawProposal/withdraw") { bearer(providerA.accessToken) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/providers/me/proposals/$withdrawProposal/withdraw") { bearer(providerB.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("WITHDRAWN") }
        }

        val directInvitation = invitationFor(providerA.accessToken, directId)
        mockMvc.get("/api/v1/customers/me/requests/$directId/proposals") { bearer(customer.accessToken) }.andExpect { jsonPath("$", hasSize<Any>(0)) }
        mockMvc.post("/api/v1/providers/me/invitations/$directInvitation/accept") { bearer(pendingProvider.accessToken) }.andExpect { status { isConflict() } }
        mockMvc.post("/api/v1/providers/me/invitations/$directInvitation/accept") { bearer(rejectedProvider.accessToken) }.andExpect { status { isConflict() } }
        mockMvc.post("/api/v1/providers/me/invitations/$directInvitation/accept") { bearer(providerB.accessToken) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/providers/me/invitations/$directInvitation/accept") { bearer(providerA.accessToken) }.andExpect {
            status { isOk() }; jsonPath("$.status") { value("ACCEPTED") }
        }
        mockMvc.post("/api/v1/providers/me/invitations/$directInvitation/accept") { bearer(providerA.accessToken) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/customers/me/requests/$directId/proposals") { bearer(customer.accessToken) }.andExpect { jsonPath("$", hasSize<Any>(0)) }
        mockMvc.post("/api/v1/providers/me/requests/$directId/proposals") {
            bearer(providerB.accessToken); contentType = MediaType.APPLICATION_JSON; content = proposalJson("840.00")
        }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("INVITATION_NOT_OWNED") } }
        val directProposal = submitProposal(providerA.accessToken, directId, "850.00")
        assertNotNull(proposalStore.findById(directProposal))

        val declineRequest = createDirect(customer.accessToken, plumbing, providerB.account.id)
        val declineInvitation = invitationFor(providerB.accessToken, declineRequest)
        mockMvc.post("/api/v1/providers/me/invitations/$declineInvitation/decline") { bearer(providerB.accessToken) }.andExpect { status { isOk() }; jsonPath("$.status") { value("DECLINED") } }
        mockMvc.post("/api/v1/providers/me/invitations/$declineInvitation/accept") { bearer(providerB.accessToken) }.andExpect { status { isConflict() } }

        val withdrawnRequest = createDirect(customer.accessToken, plumbing, providerB.account.id)
        val withdrawnInvitation = invitationFor(providerB.accessToken, withdrawnRequest)
        mockMvc.post("/api/v1/customers/me/requests/$withdrawnRequest/cancel") { bearer(customer.accessToken) }.andExpect { jsonPath("$.invitationStatus") { value("WITHDRAWN") } }
        mockMvc.post("/api/v1/providers/me/invitations/$withdrawnInvitation/accept") { bearer(providerB.accessToken) }.andExpect { status { isConflict() } }

        val notAcceptedRequest = createDirect(customer.accessToken, plumbing, providerB.account.id)
        mockMvc.post("/api/v1/providers/me/requests/$notAcceptedRequest/proposals") {
            bearer(providerB.accessToken); contentType = MediaType.APPLICATION_JSON; content = proposalJson("800.00")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("INVITATION_NOT_PENDING") } }

        mockMvc.post("/api/v1/customers/me/proposals/$directProposal/accept") { bearer(customer.accessToken) }.andExpect { status { isNotFound() } }

        val concurrentJob = createJob(customer.accessToken, electrical)
        mockMvc.post("/api/v1/customers/me/requests/$concurrentJob/request-publication") { bearer(customer.accessToken) }.andExpect { status { isOk() } }
        val concurrentPayment = initiatePayment(customer.accessToken, concurrentJob)
        val concurrentBody = callbackBody("concurrent-success", concurrentPayment.id, concurrentPayment.providerReferenceId!!, "SUCCESS", "175.50", "ETB")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<Unit> {
                    start.await()
                    paymentService.processCallback(
                        "local-test",
                        concurrentBody.toByteArray(),
                        mapOf("x-local-payment-signature" to sign(concurrentBody)),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(ServiceRequestStatus.OPEN, requestStore.findById(concurrentJob)!!.status)
        assertEquals(1, paymentStore.findByRequestId(concurrentJob).count { it.status == PostingFeeStatus.SUCCESSFUL })

        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }; jsonPath("$.paths['/api/v1/providers/me/jobs']") { exists() }
            jsonPath("$.paths['/api/v1/customers/me/proposals/{proposalId}/accept']") { doesNotExist() }
        }
    }

    private data class Initiated(val id: UUID, val amount: BigDecimal, val providerReferenceId: String?)
    private fun initiatePayment(token: String, requestId: UUID): Initiated {
        val body = mockMvc.post("/api/v1/customers/me/requests/$requestId/posting-fee/payments") { bearer(token) }
            .andExpect { status { isOk() }; jsonPath("$.amount") { value(175.50) }; jsonPath("$.status") { value("PENDING") } }
            .andReturn().response.contentAsString
        val id = UUID.fromString(JsonPath.read(body, "$.paymentId"))
        return Initiated(id, BigDecimal(JsonPath.read<Number>(body, "$.amount").toString()).setScale(2), paymentStore.findById(id)?.providerReferenceId)
    }

    private fun callback(body: String, signature: String?, expectedStatus: Int, code: String?) {
        mockMvc.post("/api/v1/payment-webhooks/local-test") {
            contentType = MediaType.APPLICATION_JSON; content = body
            header("X-Local-Payment-Signature", signature ?: sign(body))
        }.andExpect {
            status { isEqualTo(expectedStatus) }
            if (code != null) jsonPath("$.code") { value(code) }
        }
    }

    private fun callbackBody(event: String, payment: UUID, reference: String, outcome: String, amount: String, currency: String, failure: String? = null) =
        """{"eventId":"$event","paymentId":"$payment","providerReferenceId":"$reference","outcome":"$outcome","amount":$amount,"currency":"$currency"${failure?.let { ",\"failureCode\":\"$it\"" } ?: ""}}"""
    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TEST_WEBHOOK_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(body.toByteArray()))
    }

    private fun createJob(token: String, category: UUID): UUID = createRequest(token,
        """{"type":"JOB_POSTING","categoryId":"$category","description":"Repair leaking kitchen plumbing safely","generalArea":"Bole","exactLocation":"Private apartment exact address","preferredDate":"$serviceDate","budgetMin":700,"budgetMax":1200}""")
    private fun createDirect(token: String, category: UUID, provider: UUID): UUID {
        val id = createRequest(token,
            """{"type":"DIRECT_INVITATION","categoryId":"$category","providerId":"$provider","description":"Repair leaking kitchen plumbing safely","generalArea":"Bole","exactLocation":"Private apartment exact address","preferredDate":"$serviceDate","budgetMin":700,"budgetMax":1200}""")
        mockMvc.get("/api/v1/customers/me/requests/$id") { bearer(token) }.andExpect {
            jsonPath("$.status") { value("OPEN") }; jsonPath("$.invitationStatus") { value("PENDING") }; jsonPath("$.postingFeeRequired") { value(false) }
        }
        return id
    }
    private fun createRequest(token: String, json: String): UUID {
        val body = mockMvc.post("/api/v1/customers/me/requests") { bearer(token); contentType = MediaType.APPLICATION_JSON; content = json }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }
    private fun invitationFor(token: String, requestId: UUID): UUID {
        val body = mockMvc.get("/api/v1/providers/me/invitations") { bearer(token) }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val items = JsonPath.read<List<Map<String, Any>>>(body, "$.items")
        return UUID.fromString(items.first { it["requestId"] == requestId.toString() }["invitationId"].toString())
    }
    private fun submitProposal(token: String, requestId: UUID, price: String): UUID {
        val body = mockMvc.post("/api/v1/providers/me/requests/$requestId/proposals") {
            bearer(token); contentType = MediaType.APPLICATION_JSON; content = proposalJson(price)
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("PENDING") } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }
    private fun proposalJson(price: String) = """{"proposedPrice":$price,"expectedTimeline":"One working day","providerMessage":"I can complete this repair professionally","conditions":"Parts billed separately"}"""
    private fun uploadAttachment(token: String, requestId: UUID): UUID {
        val body = mockMvc.multipart("/api/v1/customers/me/requests/$requestId/attachments") {
            file(MockMultipartFile("file", "job.png", MediaType.IMAGE_PNG_VALUE, "job-photo".toByteArray())); bearer(token)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }

    private fun createCategory(token: String, name: String, am: String): UUID {
        val body = mockMvc.post("/api/v1/admin/categories") { bearer(token); contentType = MediaType.APPLICATION_JSON; content = """{"nameEn":"$name","nameAm":"$am"}""" }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        return UUID.fromString(JsonPath.read(body, "$.id"))
    }
    private fun completeProvider(result: AuthResult, area: String) {
        mockMvc.put("/api/v1/providers/me/profile") {
            bearer(result.accessToken); contentType = MediaType.APPLICATION_JSON
            content = """{"professionalDescription":"Experienced professional service provider","skills":["Repairs"],"experienceYears":6,"serviceArea":"$area","pricingSummary":"Pricing by scope"}"""
        }.andExpect { status { isOk() } }
    }
    private fun assign(result: AuthResult, category: UUID) {
        mockMvc.put("/api/v1/providers/me/categories") { bearer(result.accessToken); contentType = MediaType.APPLICATION_JSON; content = """{"categoryIds":["$category"]}""" }
            .andExpect { status { isOk() } }
    }

    private fun registerCustomer() = authService.registerCustomer(RegistrationCommand("customer-${UUID.randomUUID()}@example.com", null, "correct-horse-battery-staple"), context)
    private fun registerProvider() = authService.registerServiceProvider(RegistrationCommand("provider-${UUID.randomUUID()}@example.com", null, "correct-horse-battery-staple"), context)
    private data class Admin(val id: UUID, val token: String)
    private fun createAdmin(): Admin {
        val now = clock.instant()
        val account = accountStore.save(Account(UUID.randomUUID(), "admin-${UUID.randomUUID()}@example.com", null, passwordHash.hash("admin-password-value"), AccountStatus.ACTIVE, setOf(AccountRole.ADMIN), true, false, 1, now, now))
        return Admin(account.id, tokens.issue(account, UUID.randomUUID(), now).value)
    }
    private fun approve(result: AuthResult, admin: UUID) = setStatus(result, MarketplaceVerificationStatus.APPROVED, admin)
    private fun reject(result: AuthResult, admin: UUID) = setStatus(result, MarketplaceVerificationStatus.REJECTED, admin)
    private fun setStatus(result: AuthResult, status: MarketplaceVerificationStatus, admin: UUID) {
        val current = verificationStore.findByAccountId(result.account.id).single()
        val now = clock.instant(); val decided = status != MarketplaceVerificationStatus.PENDING
        verificationStore.save(current.copy(status = status, submittedAt = now.takeIf { decided }, reviewedAt = now.takeIf { decided }, reviewerAdminId = admin.takeIf { decided }, decisionNote = "Test decision".takeIf { decided }, updatedAt = now))
    }
    private fun MockHttpServletRequestDsl.bearer(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        private val TEST_WEBHOOK_SECRET = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        @DynamicPropertySource @JvmStatic
        fun paymentProperties(registry: DynamicPropertyRegistry) {
            registry.add("platform.posting-fee.local-test-webhook-secret") { TEST_WEBHOOK_SECRET }
        }
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
