package com.skilledservices.platform.booking

import com.skilledservices.platform.identity.domain.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.*
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BookingLifecyclePostgresIntegrationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountStore
    @Autowired lateinit var tokens: AccessTokenPort

    @Test
    fun `provider and customer complete service with idempotent append only history`() {
        val f = fixture(9, 10)
        val unrelatedProvider = provider(f.admin, f.category)
        val unrelatedCustomer = account(AccountRole.CUSTOMER, f.admin, approved = true)

        mockMvc.post("/api/v1/providers/me/bookings/${f.booking}/start") { bearer(access(unrelatedProvider)) }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("BOOKING_NOT_OWNED") } }
        mockMvc.post("/api/v1/providers/me/bookings/${f.booking}/start") { bearer(access(f.customer)) }
            .andExpect { status { isForbidden() } }

        start(f).andExpect { status { isOk() }; jsonPath("$.status") { value("IN_PROGRESS") }; jsonPath("$.startedAt") { exists() } }
        start(f).andExpect { status { isOk() }; jsonPath("$.statusHistory.length()") { value(2) } }
        assertEquals(2L, historyCount(f.booking))

        mockMvc.post("/api/v1/providers/me/bookings/${f.booking}/report-complete") { bearer(access(f.provider)) }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("COMPLETION_PENDING") }; jsonPath("$.providerReportedCompleteAt") { exists() } }
        mockMvc.post("/api/v1/customers/me/bookings/${f.booking}/confirm-completion") { bearer(access(unrelatedCustomer)) }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("BOOKING_NOT_OWNED") } }
        complete(f).andExpect { status { isOk() }; jsonPath("$.status") { value("COMPLETED") }; jsonPath("$.completedAt") { exists() }; jsonPath("$.statusHistory.length()") { value(4) } }
        complete(f).andExpect { status { isOk() }; jsonPath("$.statusHistory.length()") { value(4) } }
        assertEquals(4L, historyCount(f.booking))

        mockMvc.post("/api/v1/customers/me/bookings/${f.booking}/cancel") {
            bearer(access(f.customer)); contentType = MediaType.APPLICATION_JSON; content = """{"reason":"too late"}"""
        }.andExpect { status { isConflict() }; jsonPath("$.code") { value("BOOKING_INVALID_TRANSITION") } }

        jdbc.update("update marketplace_verifications set status='REJECTED', updated_at=now() where account_id=?", f.provider)
        mockMvc.get("/api/v1/providers/me/bookings?status=COMPLETED&page=0&size=10") { bearer(access(f.provider)) }
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) }; jsonPath("$.items[0].exactLocation") { value("Lifecycle address 12") } }
        mockMvc.get("/api/v1/customers/me/bookings?status=COMPLETED&page=0&size=10") { bearer(access(f.customer)) }
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) } }
        mockMvc.get("/api/v1/providers/me/bookings/${f.booking}") { bearer(access(unrelatedProvider)) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `customer cancellation retains history and releases the provider interval`() {
        val f = fixture(11, 12)
        discovery(f).andExpect { status { isOk() }; jsonPath("$.totalItems") { value(0) } }

        cancel(f).andExpect {
            status { isOk() }; jsonPath("$.status") { value("CANCELLED") }; jsonPath("$.cancelledAt") { exists() }
            jsonPath("$.cancellationReason") { value("Schedule changed") }; jsonPath("$.statusHistory.length()") { value(2) }
        }
        cancel(f).andExpect { status { isOk() }; jsonPath("$.statusHistory.length()") { value(2) } }
        assertEquals(2L, historyCount(f.booking))
        discovery(f).andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) } }
        mockMvc.get("/api/v1/customers/me/bookings?status=CANCELLED&page=0&size=10") { bearer(access(f.customer)) }
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(1) } }
        start(f).andExpect { status { isConflict() }; jsonPath("$.code") { value("BOOKING_INVALID_TRANSITION") } }
    }

    @Test
    fun `invalid lifecycle ordering and cancellation after start are rejected`() {
        val f = fixture(13, 14)
        complete(f).andExpect { status { isConflict() }; jsonPath("$.code") { value("BOOKING_INVALID_TRANSITION") } }
        mockMvc.post("/api/v1/providers/me/bookings/${f.booking}/report-complete") { bearer(access(f.provider)) }
            .andExpect { status { isConflict() }; jsonPath("$.code") { value("BOOKING_INVALID_TRANSITION") } }
        start(f).andExpect { status { isOk() } }
        cancel(f).andExpect { status { isConflict() }; jsonPath("$.code") { value("BOOKING_INVALID_TRANSITION") } }
        assertEquals("IN_PROGRESS", jdbc.queryForObject("select status from bookings where id=?", String::class.java, f.booking))
        assertEquals(2L, historyCount(f.booking))
    }

    private data class Fixture(val admin: UUID, val category: UUID, val customer: UUID, val provider: UUID, val booking: UUID,
        val date: LocalDate, val startHour: Int, val endHour: Int)

    private fun fixture(startHour: Int, endHour: Int): Fixture {
        val admin = rawAccount(AccountRole.ADMIN)
        val category = UUID.randomUUID()
        jdbc.update("insert into service_categories(id,name_en,name_am,active,created_at,updated_at) values (?,?,?,true,now(),now())", category, "Lifecycle-$category", "ሙያ")
        val customer = account(AccountRole.CUSTOMER, admin, approved = true)
        val provider = provider(admin, category)
        val request = UUID.randomUUID()
        val proposal = UUID.randomUUID()
        val booking = UUID.randomUUID()
        val date = LocalDate.now(ADDIS).plusDays(8)
        val start = date.atTime(startHour, 0).atZone(ADDIS).toOffsetDateTime()
        val end = date.atTime(endHour, 0).atZone(ADDIS).toOffsetDateTime()
        jdbc.update("insert into service_requests(id,customer_id,request_type,status,category_id,provider_id,description,general_area,exact_location,preferred_date,created_at,updated_at) values (?,?,'JOB_POSTING','PROVIDER_SELECTED',?,?,?,'Bole','Lifecycle address 12',?,now(),now())",
            request, customer, category, provider, "Lifecycle service request with retained private details", date)
        jdbc.update("insert into proposals(id,request_id,provider_id,proposed_price,expected_timeline,provider_message,status,created_at,updated_at) values (?,?,?,950.00,'Two hours','Lifecycle proposal','ACCEPTED',now(),now())", proposal, request, provider)
        jdbc.update("insert into bookings(id,request_id,proposal_id,customer_id,provider_id,scheduled_start,scheduled_end,agreed_price,currency,status,confirmed_at) values (?,?,?,?,?,?,?,950.00,'ETB','CONFIRMED',now())",
            booking, request, proposal, customer, provider, start, end)
        jdbc.update("insert into booking_status_history(id,booking_id,from_status,to_status,actor_account_id,actor_context,occurred_at) values (?,?,null,'CONFIRMED',?,'CUSTOMER',now())", UUID.randomUUID(), booking, customer)
        return Fixture(admin, category, customer, provider, booking, date, startHour, endHour)
    }

    private fun provider(admin: UUID, category: UUID): UUID {
        val id = account(AccountRole.SERVICE_PROVIDER, admin, approved = true)
        jdbc.update("insert into provider_profiles(account_id,professional_description,experience_years,service_area,pricing_summary,setup_complete,created_at,updated_at) values (?,?,6,'Addis Ababa','Lifecycle quote',true,now(),now())", id, "Experienced lifecycle integration provider")
        jdbc.update("insert into provider_profile_skills(provider_id,skill) values (?,?)", id, "Lifecycle")
        jdbc.update("insert into provider_service_categories(provider_id,category_id,created_at) values (?,?,now())", id, category)
        jdbc.update("insert into provider_availability(provider_id,available,updated_at) values (?,true,now())", id)
        DayOfWeek.entries.forEach { day ->
            jdbc.update("insert into provider_availability_ranges(id,provider_id,day_of_week,start_time,end_time) values (?,?,?,'08:00','17:00')", UUID.randomUUID(), id, day.name)
        }
        return id
    }

    private fun account(role: AccountRole, admin: UUID, approved: Boolean): UUID {
        val id = rawAccount(role)
        val status = if (approved) "APPROVED" else "PENDING"
        val subject = if (role == AccountRole.CUSTOMER) "CUSTOMER" else "PROVIDER"
        if (approved) jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,submitted_at,reviewed_at,reviewer_admin_id,decision_note,created_at,updated_at) values (?,?,?, ?,now(),now(),?,'approved',now(),now())", UUID.randomUUID(), id, subject, status, admin)
        else jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,created_at,updated_at) values (?,?,?,'PENDING',now(),now())", UUID.randomUUID(), id, subject)
        return id
    }

    private fun rawAccount(role: AccountRole): UUID {
        val id = UUID.randomUUID()
        val phone = if (role == AccountRole.CUSTOMER) "+251911" + id.toString().filter(Char::isDigit).padEnd(6, '1').take(6) else null
        jdbc.update("insert into accounts(id,email,phone,password_hash,status,email_verified,phone_verified,credential_version,created_at,updated_at) values (?,?,?,'hash','ACTIVE',true,false,1,now(),now())", id, "$role-$id@example.test", phone)
        jdbc.update("insert into account_roles(account_id,role) values (?,?)", id, role.name)
        return id
    }

    private fun start(f: Fixture) = mockMvc.post("/api/v1/providers/me/bookings/${f.booking}/start") { bearer(access(f.provider)) }
    private fun complete(f: Fixture) = mockMvc.post("/api/v1/customers/me/bookings/${f.booking}/confirm-completion") { bearer(access(f.customer)) }
    private fun cancel(f: Fixture) = mockMvc.post("/api/v1/customers/me/bookings/${f.booking}/cancel") {
        bearer(access(f.customer)); contentType = MediaType.APPLICATION_JSON; content = """{"reason":"Schedule changed"}"""
    }
    private fun discovery(f: Fixture) = mockMvc.get("/api/v1/providers?categoryId=${f.category}&requestedDate=${f.date}&startTime=${hour(f.startHour)}:00:00&endTime=${hour(f.endHour)}:00:00")
    private fun hour(value: Int) = value.toString().padStart(2, '0')
    private fun historyCount(booking: UUID) = jdbc.queryForObject("select count(*) from booking_status_history where booking_id=?", Long::class.java, booking)!!
    private fun access(id: UUID) = tokens.issue(accounts.findById(id)!!, UUID.randomUUID(), Instant.now()).value
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        private val ADDIS = ZoneId.of("Africa/Addis_Ababa")
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
