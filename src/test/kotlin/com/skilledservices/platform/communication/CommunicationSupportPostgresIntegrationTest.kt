package com.skilledservices.platform.communication

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.domain.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CommunicationSupportPostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountStore
    @Autowired lateinit var tokens: AccessTokenPort

    @Test
    fun `relationship scoped messaging is private paginated readable and blocked without deleting history`() {
        val fixture = directFixture()
        val unrelated = rawAccount(AccountRole.CUSTOMER)
        val created = mockMvc.post("/api/v1/me/conversations") {
            bearer(fixture.customer); json("""{"requestId":"${fixture.request}","counterpartAccountId":"${fixture.provider}"}""")
        }.andExpect {
            status { isCreated() }; jsonPath("$.customerId") { value(fixture.customer.toString()) }
            jsonPath("$.exactLocation") { doesNotExist() }; jsonPath("$.phone") { doesNotExist() }
        }.andReturn().response.contentAsString
        val conversation = UUID.fromString(JsonPath.read(created, "$.id"))

        mockMvc.get("/api/v1/me/conversations/$conversation") { bearer(unrelated) }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("CONVERSATION_ACCESS_DENIED") } }
        mockMvc.post("/api/v1/me/conversations/$conversation/messages") {
            bearer(fixture.customer); json("""{"body":"Please bring the standard tools."}""")
        }.andExpect {
            status { isCreated() }; jsonPath("$.body") { value("Please bring the standard tools.") }
            jsonPath("$.exactLocation") { doesNotExist() }; jsonPath("$.phone") { doesNotExist() }
        }
        mockMvc.get("/api/v1/me/conversations/$conversation/messages?page=0&size=10") { bearer(fixture.provider) }
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) }; jsonPath("$.items[0].readAt") { doesNotExist() } }
        mockMvc.post("/api/v1/me/conversations/$conversation/read") { bearer(fixture.provider) }
            .andExpect { status { isOk() }; jsonPath("$.updatedMessages") { value(1) } }
        mockMvc.get("/api/v1/me/conversations/$conversation/messages?page=0&size=10") { bearer(fixture.customer) }
            .andExpect { status { isOk() }; jsonPath("$.items[0].readAt") { exists() } }

        mockMvc.post("/api/v1/me/blocks") {
            bearer(fixture.provider); json("""{"blockedAccountId":"${fixture.customer}","reasonCategory":"OTHER"}""")
        }.andExpect { status { isCreated() } }
        mockMvc.post("/api/v1/me/blocks") {
            bearer(fixture.provider); json("""{"blockedAccountId":"${fixture.customer}"}""")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("BLOCK_ALREADY_EXISTS") } }
        mockMvc.post("/api/v1/me/conversations/$conversation/messages") {
            bearer(fixture.customer); json("""{"body":"This must be blocked."}""")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("INTERACTION_BLOCKED") } }
        mockMvc.post("/api/v1/customers/me/requests") {
            bearer(fixture.customer); json("""{"type":"DIRECT_INVITATION","categoryId":"${fixture.category}","providerId":"${fixture.provider}","description":"A second legitimate direct request that blocking must prevent","generalArea":"Bole","exactLocation":"Another private address","preferredDate":"${LocalDate.now().plusDays(5)}"}""")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("INTERACTION_BLOCKED") } }
        assertEquals(1L, jdbc.queryForObject("select count(*) from messages where conversation_id=?", Long::class.java, conversation))
        assertEquals(1L, jdbc.queryForObject("select count(*) from service_requests where id=?", Long::class.java, fixture.request))

        mockMvc.delete("/api/v1/me/blocks/${fixture.customer}") { bearer(fixture.provider) }.andExpect { status { isNoContent() } }
        mockMvc.post("/api/v1/me/conversations/$conversation/messages") {
            bearer(fixture.customer); json("""{"body":"Communication restored."}""")
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `booking dispute atomically enters review prevents duplicates and supports admin resolution`() {
        val fixture = bookingFixture()
        val unrelated = rawAccount(AccountRole.CUSTOMER)
        mockMvc.post("/api/v1/me/support-cases") {
            bearer(unrelated); json("""{"bookingId":"${fixture.booking}","type":"BOOKING_DISPUTE","description":"Not my Booking"}""")
        }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("SUPPORT_CASE_ACCESS_DENIED") } }

        val created = mockMvc.post("/api/v1/me/support-cases") {
            bearer(fixture.customer); json("""{"bookingId":"${fixture.booking}","type":"BOOKING_DISPUTE","description":"The completed work needs support review."}""")
        }.andExpect {
            status { isCreated() }; jsonPath("$.status") { value("OPEN") }; jsonPath("$.internalNote") { doesNotExist() }
        }.andReturn().response.contentAsString
        val caseId = UUID.fromString(JsonPath.read(created, "$.id"))
        assertEquals("UNDER_REVIEW", jdbc.queryForObject("select status from bookings where id=?", String::class.java, fixture.booking))
        assertEquals(2L, jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Long::class.java, fixture.booking))

        mockMvc.post("/api/v1/me/support-cases") {
            bearer(fixture.provider); json("""{"bookingId":"${fixture.booking}","type":"BOOKING_DISPUTE","description":"Duplicate active dispute"}""")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("ACTIVE_DISPUTE_EXISTS") } }
        mockMvc.get("/api/v1/admin/support-cases") { bearer(fixture.customer) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/support-cases/$caseId/review") {
            bearer(fixture.admin); json("""{"status":"IN_REVIEW","internalNote":"Admin-only investigation note"}""")
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("IN_REVIEW") }; jsonPath("$.internalNote") { value("Admin-only investigation note") } }
        mockMvc.post("/api/v1/admin/support-cases/$caseId/review") {
            bearer(fixture.admin); json("""{"status":"RESOLVED","internalNote":"Evidence reviewed","resolutionOutcome":"Case reviewed; no financial action exists in this platform."}""")
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("RESOLVED") }; jsonPath("$.resolvedAt") { exists() } }
        mockMvc.get("/api/v1/me/support-cases/$caseId") { bearer(fixture.provider) }
            .andExpect { status { isOk() }; jsonPath("$.internalNote") { doesNotExist() }; jsonPath("$.resolutionOutcome") { exists() } }
        assertEquals("UNDER_REVIEW", jdbc.queryForObject("select status from bookings where id=?", String::class.java, fixture.booking))
    }

    @Test
    fun `reports moderation notifications and AM EN preferences remain account scoped`() {
        val fixture = directFixture()
        val report = mockMvc.post("/api/v1/me/reports") {
            bearer(fixture.customer); json("""{"reportedAccountId":"${fixture.provider}","relatedEntityType":"REQUEST","relatedEntityId":"${fixture.request}","category":"OTHER","description":"Please review this marketplace interaction."}""")
        }.andExpect { status { isCreated() }; jsonPath("$.status") { value("OPEN") } }
            .andReturn().response.contentAsString
        val reportId = UUID.fromString(JsonPath.read(report, "$.id"))

        mockMvc.post("/api/v1/admin/reports/$reportId/review") {
            bearer(fixture.customer); json("""{"status":"RESOLVED","moderationNote":"No"}""")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/reports/$reportId/review") {
            bearer(fixture.admin); json("""{"status":"RESOLVED","moderationNote":"Reviewed against platform policy."}""")
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("RESOLVED") } }
        mockMvc.get("/api/v1/me/notifications?unreadOnly=true&page=0&size=20") { bearer(fixture.customer) }
            .andExpect { status { isOk() }; jsonPath("$.items[0].type") { value("REPORT_REVIEWED") }; jsonPath("$.items[0].description") { doesNotExist() } }
        mockMvc.get("/api/v1/me/notifications") { bearer(fixture.provider) }
            .andExpect { status { isOk() }; jsonPath("$.items[?(@.type == 'REPORT_REVIEWED')]") { isEmpty() } }

        mockMvc.put("/api/v1/me/preferences") {
            bearer(fixture.customer); json("""{"preferredLanguage":"AM","marketplaceNotificationsEnabled":false,"communicationNotificationsEnabled":true,"supportNotificationsEnabled":true}""")
        }.andExpect { status { isOk() }; jsonPath("$.preferredLanguage") { value("AM") }; jsonPath("$.marketplaceNotificationsEnabled") { value(false) } }
        mockMvc.get("/api/v1/me/preferences") { bearer(fixture.provider) }
            .andExpect { status { isOk() }; jsonPath("$.preferredLanguage") { value("EN") } }
    }

    private data class Fixture(val admin: UUID, val customer: UUID, val provider: UUID, val request: UUID,
        val category: UUID, val booking: UUID? = null)

    private fun directFixture(): Fixture {
        val admin = rawAccount(AccountRole.ADMIN)
        val customer = rawAccount(AccountRole.CUSTOMER)
        val provider = rawAccount(AccountRole.SERVICE_PROVIDER)
        val category = category()
        approve(customer, "CUSTOMER", admin)
        approve(provider, "PROVIDER", admin)
        jdbc.update("insert into provider_profiles(account_id,professional_description,experience_years,service_area,pricing_summary,setup_complete,created_at,updated_at) values (?,'Communication provider',3,'Addis Ababa','Quote',true,now(),now())", provider)
        jdbc.update("insert into provider_service_categories(provider_id,category_id,created_at) values (?,?,now())", provider, category)
        val request = UUID.randomUUID()
        jdbc.update("""insert into service_requests(id,customer_id,request_type,status,category_id,provider_id,description,general_area,exact_location,preferred_date,created_at,updated_at)
            values (?,?,'DIRECT_INVITATION','OPEN',?,?,?,'Bole','Private house location',?,now(),now())""".trimIndent(),
            request, customer, category, provider, "Relationship-backed communication request", LocalDate.now().plusDays(3))
        jdbc.update("insert into invitations(id,request_id,provider_id,status,created_at,updated_at) values (?,?,?,'PENDING',now(),now())", UUID.randomUUID(), request, provider)
        return Fixture(admin, customer, provider, request, category)
    }

    private fun bookingFixture(): Fixture {
        val base = directFixture()
        val proposal = UUID.randomUUID()
        val booking = UUID.randomUUID()
        jdbc.update("update invitations set status='ACCEPTED' where request_id=?", base.request)
        jdbc.update("update service_requests set status='PROVIDER_SELECTED' where id=?", base.request)
        jdbc.update("insert into proposals(id,request_id,provider_id,proposed_price,expected_timeline,provider_message,status,created_at,updated_at) values (?,?,?,500.00,'One day','Dispute fixture proposal','ACCEPTED',now(),now())", proposal, base.request, base.provider)
        val start = Instant.now().plusSeconds(86_400).atOffset(ZoneOffset.UTC)
        jdbc.update("insert into bookings(id,request_id,proposal_id,customer_id,provider_id,scheduled_start,scheduled_end,agreed_price,currency,status,confirmed_at) values (?,?,?,?,?,?,?,500.00,'ETB','CONFIRMED',now())",
            booking, base.request, proposal, base.customer, base.provider, start, start.plusSeconds(3600))
        jdbc.update("insert into booking_status_history(id,booking_id,from_status,to_status,actor_account_id,actor_context,occurred_at) values (?,?,null,'CONFIRMED',?,'CUSTOMER',now())", UUID.randomUUID(), booking, base.customer)
        return base.copy(booking = booking)
    }

    private fun category(): UUID = UUID.randomUUID().also {
        jdbc.update("insert into service_categories(id,name_en,name_am,active,created_at,updated_at) values (?,?,?,true,now(),now())", it, "Communication-$it", "አገልግሎት")
    }

    private fun approve(accountId: UUID, subject: String, adminId: UUID) {
        jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,submitted_at,reviewed_at,reviewer_admin_id,decision_note,created_at,updated_at) values (?, ?, ?, 'APPROVED',now(),now(),?,'approved',now(),now())",
            UUID.randomUUID(), accountId, subject, adminId)
    }

    private fun rawAccount(role: AccountRole): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into accounts(id,email,password_hash,status,email_verified,phone_verified,credential_version,created_at,updated_at) values (?,?,'hash','ACTIVE',true,false,1,now(),now())", id, "$role-$id@example.test")
        jdbc.update("insert into account_roles(account_id,role) values (?,?)", id, role.name)
        return id
    }

    private fun access(id: UUID) = tokens.issue(accounts.findById(id)!!, UUID.randomUUID(), Instant.now()).value
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(id: UUID) { header("Authorization", "Bearer ${access(id)}") }
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.json(value: String) { contentType = MediaType.APPLICATION_JSON; content = value }

    companion object {
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
