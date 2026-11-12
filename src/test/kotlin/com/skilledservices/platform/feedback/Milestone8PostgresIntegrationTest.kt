package com.skilledservices.platform.feedback

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.identity.domain.*
import com.skilledservices.platform.marketplace.application.*
import com.skilledservices.platform.marketplace.domain.AiSuggestionUnavailableException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(Milestone8PostgresIntegrationTest.AiTestConfiguration::class)
class Milestone8PostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountStore
    @Autowired lateinit var tokens: AccessTokenPort
    @Autowired lateinit var fakeAi: FakeCategorySuggestionPort

    @BeforeEach
    fun resetAi() { fakeAi.response = RawCategorySuggestion(null, null, null); fakeAi.unavailable = false }

    @Test
    fun `reciprocal feedback requires completed booking aggregates provider ratings and preserves moderated evidence`() {
        val fixture = fixture(completed = true)
        val unrelated = rawAccount(AccountRole.CUSTOMER)
        val pending = fixture(completed = false)

        mockMvc.post("/api/v1/customers/me/bookings/${pending.booking}/provider-feedback") {
            bearer(pending.customer); json("""{"rating":5,"comment":"Too early"}""")
        }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("FEEDBACK_NOT_ALLOWED") } }
        mockMvc.post("/api/v1/customers/me/bookings/${fixture.booking}/provider-feedback") {
            bearer(unrelated); json("""{"rating":5}""")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/customers/me/bookings/${fixture.booking}/provider-feedback") {
            bearer(fixture.customer); json("""{"rating":0}""")
        }.andExpect { status { isBadRequest() } }

        val created = mockMvc.post("/api/v1/customers/me/bookings/${fixture.booking}/provider-feedback") {
            bearer(fixture.customer); json("""{"rating":5,"comment":"Excellent completed service."}""")
        }.andExpect { status { isCreated() }; jsonPath("$.direction") { value("CUSTOMER_TO_PROVIDER") } }
            .andReturn().response.contentAsString
        val feedbackId = UUID.fromString(JsonPath.read(created, "$.id"))
        mockMvc.post("/api/v1/customers/me/bookings/${fixture.booking}/provider-feedback") {
            bearer(fixture.customer); json("""{"rating":4}""")
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("FEEDBACK_ALREADY_EXISTS") } }
        mockMvc.post("/api/v1/providers/me/bookings/${fixture.booking}/customer-feedback") {
            bearer(fixture.provider); json("""{"rating":2,"comment":"Ease of working with feedback."}""")
        }.andExpect { status { isCreated() }; jsonPath("$.direction") { value("PROVIDER_TO_CUSTOMER") } }

        mockMvc.get("/api/v1/providers/${fixture.provider}")
            .andExpect { status { isOk() }; jsonPath("$.averageRating") { value(5.0) }; jsonPath("$.ratingCount") { value(1) } }
        mockMvc.get("/api/v1/providers?minRating=4&sort=rating,desc&page=0&size=20")
            .andExpect { status { isOk() }; jsonPath("$.items[?(@.providerId == '${fixture.provider}')].ratingCount") { value(1) } }
        mockMvc.get("/api/v1/providers/${fixture.provider}/reviews?page=0&size=20")
            .andExpect {
                status { isOk() }; jsonPath("$.totalItems") { value(1) }; jsonPath("$.items[0].reviewerLabel") { value("Verified customer") }
                jsonPath("$.items[0].raterAccountId") { doesNotExist() }; jsonPath("$.items[0].bookingId") { doesNotExist() }
            }
        mockMvc.get("/api/v1/providers/${pending.provider}")
            .andExpect { status { isOk() }; jsonPath("$.averageRating") { doesNotExist() }; jsonPath("$.ratingCount") { value(0) } }

        mockMvc.post("/api/v1/admin/feedback/$feedbackId/moderation") {
            bearer(fixture.admin); json("""{"visible":false,"reason":"Public content moderation policy"}""")
        }.andExpect { status { isOk() }; jsonPath("$.visible") { value(false) }; jsonPath("$.rating") { value(5) } }
        mockMvc.get("/api/v1/providers/${fixture.provider}/reviews?page=0&size=20")
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(0) } }
        assertEquals(1L, jdbc.queryForObject("select count(*) from booking_feedback where id=?", Long::class.java, feedbackId))
    }

    @Test
    fun `admin dashboard audit filters and account status operations are authorized and data backed`() {
        val admin = rawAccount(AccountRole.ADMIN)
        val customer = rawAccount(AccountRole.CUSTOMER)
        val target = rawAccount(AccountRole.SERVICE_PROVIDER)
        mockMvc.get("/api/v1/admin/dashboard") { bearer(customer) }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/accounts/$target/status") {
            bearer(customer); json("""{"status":"SUSPENDED"}""")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/accounts/$target/status") {
            bearer(admin); json("""{"status":"SUSPENDED"}""")
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("SUSPENDED") }; jsonPath("$.roles[0]") { value("SERVICE_PROVIDER") } }
        assertEquals("SUSPENDED", jdbc.queryForObject("select status from accounts where id=?", String::class.java, target))

        val expectedCustomers = jdbc.queryForObject("select count(distinct account_id) from account_roles where role='CUSTOMER'", Long::class.java)!!
        val expectedProviders = jdbc.queryForObject("select count(distinct account_id) from account_roles where role='SERVICE_PROVIDER'", Long::class.java)!!
        mockMvc.get("/api/v1/admin/dashboard") { bearer(admin) }.andExpect {
            status { isOk() }; jsonPath("$.totalCustomers") { value(expectedCustomers) }; jsonPath("$.totalProviders") { value(expectedProviders) }
            jsonPath("$.verificationCounts") { exists() }; jsonPath("$.bookingCounts") { exists() }; jsonPath("$.postingFeeCounts") { exists() }
        }
        mockMvc.get("/api/v1/admin/audit-events?actorId=$admin&eventType=ACCOUNT_STATUS_CHANGED&page=0&size=10") { bearer(admin) }
            .andExpect {
                status { isOk() }; jsonPath("$.totalItems") { value(1) }; jsonPath("$.items[0].subjectId") { value(target.toString()) }
                jsonPath("$.items[0].messageBody") { doesNotExist() }; jsonPath("$.items[0].paymentPayload") { doesNotExist() }
            }
    }

    @Test
    fun `AI suggestion validates active category output degrades safely and never persists a request`() {
        val customer = rawAccount(AccountRole.CUSTOMER)
        val active = category(true)
        val inactive = category(false)
        val before = jdbc.queryForObject("select count(*) from service_requests", Long::class.java)!!

        fakeAi.response = RawCategorySuggestion(active.toString(), 0.91, "The description matches this active category.")
        mockMvc.post("/api/v1/customers/me/category-suggestions") {
            bearer(customer); json("""{"description":"I need a qualified technician to repair a leaking kitchen pipe."}""")
        }.andExpect {
            status { isOk() }; jsonPath("$.suggestedCategoryId") { value(active.toString()) }; jsonPath("$.advisory") { value(true) }
        }
        assertEquals(before, jdbc.queryForObject("select count(*) from service_requests", Long::class.java))

        fakeAi.response = RawCategorySuggestion(UUID.randomUUID().toString(), 0.5, "Invented")
        suggest(customer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AI_SUGGESTION_INVALID") } }
        fakeAi.response = RawCategorySuggestion(inactive.toString(), 0.5, "Inactive")
        suggest(customer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AI_SUGGESTION_INVALID") } }
        fakeAi.response = RawCategorySuggestion(null, null, null)
        suggest(customer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("AI_SUGGESTION_INVALID") } }
        fakeAi.unavailable = true
        suggest(customer).andExpect { status { isServiceUnavailable() }; jsonPath("$.code") { value("AI_SUGGESTION_UNAVAILABLE") } }
    }

    private data class Fixture(val admin: UUID, val customer: UUID, val provider: UUID, val category: UUID, val booking: UUID)

    private fun fixture(completed: Boolean): Fixture {
        val admin = rawAccount(AccountRole.ADMIN)
        val customer = rawAccount(AccountRole.CUSTOMER)
        val provider = rawAccount(AccountRole.SERVICE_PROVIDER)
        approve(customer, "CUSTOMER", admin); approve(provider, "PROVIDER", admin)
        val category = category(true)
        jdbc.update("insert into provider_profiles(account_id,professional_description,experience_years,service_area,pricing_summary,setup_complete,created_at,updated_at) values (?,'Feedback provider',5,'Addis Ababa','Fixed quote',true,now(),now())", provider)
        jdbc.update("insert into provider_profile_skills(provider_id,skill) values (?,'Feedback service')", provider)
        jdbc.update("insert into provider_service_categories(provider_id,category_id,created_at) values (?,?,now())", provider, category)
        val request = UUID.randomUUID(); val proposal = UUID.randomUUID(); val booking = UUID.randomUUID()
        jdbc.update("insert into service_requests(id,customer_id,request_type,status,category_id,provider_id,description,general_area,exact_location,preferred_date,created_at,updated_at) values (?,?,'JOB_POSTING','PROVIDER_SELECTED',?,?,?,'Bole','Private feedback fixture address',current_date,now(),now())",
            request, customer, category, provider, "Completed service feedback fixture")
        jdbc.update("insert into proposals(id,request_id,provider_id,proposed_price,expected_timeline,provider_message,status,created_at,updated_at) values (?,?,?,700.00,'One day','Feedback proposal','ACCEPTED',now(),now())", proposal, request, provider)
        val start = OffsetDateTime.now().minusDays(2); val end = start.plusHours(2)
        if (completed) {
            jdbc.update("insert into bookings(id,request_id,proposal_id,customer_id,provider_id,scheduled_start,scheduled_end,agreed_price,currency,status,confirmed_at,started_at,provider_reported_complete_at,completed_at) values (?,?,?,?,?,?,?,700.00,'ETB','COMPLETED',now()-interval '3 day',now()-interval '2 day',now()-interval '2 day',now()-interval '2 day')",
                booking, request, proposal, customer, provider, start, end)
        } else {
            jdbc.update("insert into bookings(id,request_id,proposal_id,customer_id,provider_id,scheduled_start,scheduled_end,agreed_price,currency,status,confirmed_at) values (?,?,?,?,?,?,?,700.00,'ETB','CONFIRMED',now())",
                booking, request, proposal, customer, provider, start.plusDays(5), end.plusDays(5))
        }
        jdbc.update("insert into booking_status_history(id,booking_id,from_status,to_status,actor_account_id,actor_context,occurred_at) values (?,?,null,?,?, 'CUSTOMER',now())",
            UUID.randomUUID(), booking, if (completed) "COMPLETED" else "CONFIRMED", customer)
        return Fixture(admin, customer, provider, category, booking)
    }

    private fun category(active: Boolean): UUID = UUID.randomUUID().also {
        jdbc.update("insert into service_categories(id,name_en,name_am,active,created_at,updated_at) values (?,?,?, ?,now(),now())", it, "Milestone8-$it", "አገልግሎት", active)
    }
    private fun approve(accountId: UUID, subject: String, adminId: UUID) {
        jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,submitted_at,reviewed_at,reviewer_admin_id,decision_note,created_at,updated_at) values (?, ?, ?, 'APPROVED',now(),now(),?,'approved',now(),now())",
            UUID.randomUUID(), accountId, subject, adminId)
    }
    private fun rawAccount(role: AccountRole): UUID = UUID.randomUUID().also {
        jdbc.update("insert into accounts(id,email,password_hash,status,email_verified,phone_verified,credential_version,created_at,updated_at) values (?,?,'hash','ACTIVE',true,false,1,now(),now())", it, "$role-$it@example.test")
        jdbc.update("insert into account_roles(account_id,role) values (?,?)", it, role.name)
    }
    private fun suggest(customer: UUID) = mockMvc.post("/api/v1/customers/me/category-suggestions") {
        bearer(customer); json("""{"description":"I need a technician for a detailed home repair service."}""")
    }
    private fun access(id: UUID) = tokens.issue(accounts.findById(id)!!, UUID.randomUUID(), Instant.now()).value
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(id: UUID) { header("Authorization", "Bearer ${access(id)}") }
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.json(value: String) { contentType = MediaType.APPLICATION_JSON; content = value }

    class FakeCategorySuggestionPort : CategorySuggestionPort {
        var response = RawCategorySuggestion(null, null, null)
        var unavailable = false
        override fun suggest(description: String, candidates: List<CategorySuggestionCandidate>): RawCategorySuggestion {
            if (unavailable) throw AiSuggestionUnavailableException()
            return response
        }
    }

    @TestConfiguration
    class AiTestConfiguration {
        @Bean @Primary fun categorySuggestionPort() = FakeCategorySuggestionPort()
    }

    companion object {
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
