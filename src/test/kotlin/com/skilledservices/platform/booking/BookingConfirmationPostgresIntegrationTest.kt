package com.skilledservices.platform.booking

import com.jayway.jsonpath.JsonPath
import com.skilledservices.platform.booking.application.BookingService
import com.skilledservices.platform.booking.application.ConfirmBookingCommand
import com.skilledservices.platform.booking.domain.*
import com.skilledservices.platform.identity.domain.*
import com.skilledservices.platform.requests.domain.ProviderNotEligibleException
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
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BookingConfirmationPostgresIntegrationTest {
    @Autowired lateinit var bookingService: BookingService
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountStore
    @Autowired lateinit var tokens: AccessTokenPort

    @Test
    fun `job proposal acceptance is atomic idempotent private and permits adjacent bookings`() {
        val fixture = fixture()
        val customer = createCustomer(fixture.admin)
        val provider = createProvider(fixture)
        val losingProvider = createAccount(AccountRole.SERVICE_PROVIDER, fixture.admin, approved = false)
        val request = createRequest(customer, fixture.category, "JOB_POSTING", null)
        val proposal = createProposal(request, provider)
        val losingProposal = createProposal(request, losingProvider)
        val interval = intervalFor(provider, 9, 10)

        val response = accept(customer, proposal, interval)
        val bookingId = UUID.fromString(JsonPath.read(response, "$.id"))
        assertEquals("ACCEPTED", jdbc.queryForObject("select status from proposals where id = ?", String::class.java, proposal))
        assertEquals("REJECTED", jdbc.queryForObject("select status from proposals where id = ?", String::class.java, losingProposal))
        assertEquals("PROVIDER_SELECTED", jdbc.queryForObject("select status from service_requests where id = ?", String::class.java, request))
        assertEquals(1L, jdbc.queryForObject("select count(*) from bookings where request_id = ?", Long::class.java, request))

        val again = accept(customer, proposal, interval)
        assertEquals(bookingId.toString(), JsonPath.read(again, "$.id"))
        assertEquals(1L, jdbc.queryForObject("select count(*) from bookings where request_id = ?", Long::class.java, request))
        assertFailsWith<ProposalNotAcceptableException> {
            bookingService.confirm(customer, losingProposal, ConfirmBookingCommand(interval.first, interval.second))
        }

        mockMvc.get("/api/v1/providers/me/bookings/$bookingId") { bearer(access(provider)) }.andExpect {
            status { isOk() }; jsonPath("$.exactLocation") { value("Private apartment 42") }
            jsonPath("$.customerPhone") { value(jdbc.queryForObject("select phone from accounts where id=?", String::class.java, customer)) }
        }
        mockMvc.get("/api/v1/providers/me/bookings/$bookingId") { bearer(access(losingProvider)) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/customers/me/bookings/$bookingId") { bearer(access(createCustomer(fixture.admin))) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/providers?categoryId=${fixture.category}&requestedDate=${interval.first.atZoneSameInstant(ADDIS).toLocalDate()}&startTime=09:00:00&endTime=10:00:00")
            .andExpect { status { isOk() }; jsonPath("$.totalItems") { value(0) } }

        val adjacentCustomer = createCustomer(fixture.admin)
        val adjacentRequest = createRequest(adjacentCustomer, fixture.category, "JOB_POSTING", null)
        val adjacentProposal = createProposal(adjacentRequest, provider)
        val adjacent = intervalFor(provider, 10, 11)
        bookingService.confirm(adjacentCustomer, adjacentProposal, ConfirmBookingCommand(adjacent.first, adjacent.second))
        assertEquals(2L, jdbc.queryForObject("select count(*) from bookings where provider_id = ?", Long::class.java, provider))
    }

    @Test
    fun `direct invitation confirms while invalid unavailable and terminal proposals are rejected`() {
        val fixture = fixture()
        val customer = createCustomer(fixture.admin)
        val provider = createProvider(fixture)
        val direct = createRequest(customer, fixture.category, "DIRECT_INVITATION", provider)
        jdbc.update("insert into invitations(id,request_id,provider_id,status,created_at,updated_at) values (?,?,?,'ACCEPTED',now(),now())", UUID.randomUUID(), direct, provider)
        val proposal = createProposal(direct, provider)
        val interval = intervalFor(provider, 11, 12)
        val confirmed = bookingService.confirm(customer, proposal, ConfirmBookingCommand(interval.first, interval.second))
        assertEquals("CONFIRMED", confirmed.booking.status.name)

        val outsideRequest = createRequest(createCustomer(fixture.admin), fixture.category, "JOB_POSTING", null)
        val outsideProposal = createProposal(outsideRequest, provider)
        val outside = intervalFor(provider, 18, 19)
        assertFailsWith<com.skilledservices.platform.booking.domain.ProviderNotAvailableException> {
            bookingService.confirm(jdbc.queryForObject("select customer_id from service_requests where id=?", UUID::class.java, outsideRequest)!!,
                outsideProposal, ConfirmBookingCommand(outside.first, outside.second))
        }
        assertFailsWith<com.skilledservices.platform.booking.domain.BookingScheduleInvalidException> {
            bookingService.confirm(customer, proposal, ConfirmBookingCommand(interval.second, interval.first))
        }
        assertFailsWith<BookingNotOwnedException> {
            bookingService.confirm(createCustomer(fixture.admin), proposal, ConfirmBookingCommand(interval.first, interval.second))
        }
    }

    @Test
    fun `confirmation rechecks customer provider request and proposal state`() {
        val fixture = fixture()
        val interval = LocalDate.now(ADDIS).plusDays(7).atTime(14, 0).atZone(ADDIS).toOffsetDateTime() to
            LocalDate.now(ADDIS).plusDays(7).atTime(15, 0).atZone(ADDIS).toOffsetDateTime()

        val pendingCustomer = createAccount(AccountRole.CUSTOMER, fixture.admin, approved = false)
        val approvedProvider = createProvider(fixture)
        val pendingCustomerProposal = createProposal(createRequest(pendingCustomer, fixture.category, "JOB_POSTING", null), approvedProvider)
        assertFailsWith<BookingNotOwnedException> {
            bookingService.confirm(pendingCustomer, pendingCustomerProposal, ConfirmBookingCommand(interval.first, interval.second))
        }

        val customer = createCustomer(fixture.admin)
        val rejectedProvider = createProvider(fixture)
        jdbc.update("update marketplace_verifications set status='REJECTED', updated_at=now() where account_id=?", rejectedProvider)
        val rejectedProviderProposal = createProposal(createRequest(customer, fixture.category, "JOB_POSTING", null), rejectedProvider)
        assertFailsWith<ProviderNotEligibleException> {
            bookingService.confirm(customer, rejectedProviderProposal, ConfirmBookingCommand(interval.first, interval.second))
        }

        val deactivatedProvider = createProvider(fixture)
        jdbc.update("update accounts set status='DEACTIVATED', updated_at=now() where id=?", deactivatedProvider)
        val deactivatedProposal = createProposal(createRequest(customer, fixture.category, "JOB_POSTING", null), deactivatedProvider)
        assertFailsWith<ProviderNotEligibleException> {
            bookingService.confirm(customer, deactivatedProposal, ConfirmBookingCommand(interval.first, interval.second))
        }

        val activeProvider = createProvider(fixture)
        val cancelledRequest = createRequest(customer, fixture.category, "JOB_POSTING", null)
        val cancelledProposal = createProposal(cancelledRequest, activeProvider)
        jdbc.update("update service_requests set status='CANCELLED', cancelled_at=now(), updated_at=now() where id=?", cancelledRequest)
        assertFailsWith<ProposalNotAcceptableException> {
            bookingService.confirm(customer, cancelledProposal, ConfirmBookingCommand(interval.first, interval.second))
        }

        val openRequest = createRequest(customer, fixture.category, "JOB_POSTING", null)
        val rejectedProposal = createProposal(openRequest, activeProvider)
        jdbc.update("update proposals set status='REJECTED', rejected_at=now(), updated_at=now() where id=?", rejectedProposal)
        assertFailsWith<ProposalNotAcceptableException> {
            bookingService.confirm(customer, rejectedProposal, ConfirmBookingCommand(interval.first, interval.second))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from bookings where request_id in (?,?)", Long::class.java, cancelledRequest, openRequest))
    }

    @Test
    fun `concurrent overlapping confirmations serialize on provider schedule and exactly one succeeds`() {
        val fixture = fixture()
        val provider = createProvider(fixture)
        val firstCustomer = createCustomer(fixture.admin)
        val secondCustomer = createCustomer(fixture.admin)
        val firstProposal = createProposal(createRequest(firstCustomer, fixture.category, "JOB_POSTING", null), provider)
        val secondProposal = createProposal(createRequest(secondCustomer, fixture.category, "JOB_POSTING", null), provider)
        val interval = intervalFor(provider, 13, 14)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = listOf(firstCustomer to firstProposal, secondCustomer to secondProposal).map { (customer, proposal) ->
            executor.submit<Result<UUID>> {
                start.await()
                runCatching { bookingService.confirm(customer, proposal, ConfirmBookingCommand(interval.first, interval.second)).booking.id }
            }
        }
        start.countDown()
        val results = futures.map { it.get() }
        executor.shutdown()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is BookingScheduleConflictException })
        assertEquals(1L, jdbc.queryForObject("select count(*) from bookings where provider_id=? and scheduled_start < ? and scheduled_end > ?", Long::class.java,
            provider, interval.second.withOffsetSameInstant(ZoneOffset.UTC), interval.first.withOffsetSameInstant(ZoneOffset.UTC)))
    }

    private data class Fixture(val admin: UUID, val category: UUID)
    private fun fixture(): Fixture {
        val admin = createRawAccount(AccountRole.ADMIN, "+251900${UUID.randomUUID().toString().takeLast(6).replace(Regex("[^0-9]"), "1")}")
        val category = UUID.randomUUID()
        jdbc.update("insert into service_categories(id,name_en,name_am,active,created_at,updated_at) values (?,?,?,true,now(),now())", category, "Category-$category", "ሙያ")
        return Fixture(admin, category)
    }

    private fun createCustomer(admin: UUID): UUID = createAccount(AccountRole.CUSTOMER, admin, approved = true)
    private fun createProvider(fixture: Fixture): UUID {
        val id = createAccount(AccountRole.SERVICE_PROVIDER, fixture.admin, approved = true)
        jdbc.update("insert into provider_profiles(account_id,professional_description,experience_years,service_area,pricing_summary,setup_complete,created_at,updated_at) values (?,?,5,'Addis Ababa','Fixed quote',true,now(),now())", id, "Experienced professional provider for booking tests")
        jdbc.update("insert into provider_profile_skills(provider_id,skill) values (?,?)", id, "Testing")
        jdbc.update("insert into provider_service_categories(provider_id,category_id,created_at) values (?,?,now())", id, fixture.category)
        jdbc.update("insert into provider_availability(provider_id,available,updated_at) values (?,true,now())", id)
        DayOfWeek.entries.forEach { day ->
            jdbc.update("insert into provider_availability_ranges(id,provider_id,day_of_week,start_time,end_time) values (?,?,?,'08:00','17:00')", UUID.randomUUID(), id, day.name)
        }
        return id
    }

    private fun createAccount(role: AccountRole, admin: UUID, approved: Boolean): UUID {
        val id = createRawAccount(role, if (role == AccountRole.CUSTOMER) customerPhone(idSeed()) else null)
        if (role != AccountRole.ADMIN) {
            val status = if (approved) "APPROVED" else "PENDING"
            val subject = if (role == AccountRole.CUSTOMER) "CUSTOMER" else "PROVIDER"
            if (approved) jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,submitted_at,reviewed_at,reviewer_admin_id,decision_note,created_at,updated_at) values (?,?,?, ?,now(),now(),?,'approved',now(),now())", UUID.randomUUID(), id, subject, status, admin)
            else jdbc.update("insert into marketplace_verifications(id,account_id,subject_role,status,created_at,updated_at) values (?,?,?,'PENDING',now(),now())", UUID.randomUUID(), id, subject)
        }
        return id
    }

    private fun idSeed() = UUID.randomUUID()
    private fun customerPhone(seed: UUID) = "+251911" + seed.toString().filter(Char::isDigit).padEnd(6, '1').take(6)
    private fun createRawAccount(role: AccountRole, phone: String?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into accounts(id,email,phone,password_hash,status,email_verified,phone_verified,credential_version,created_at,updated_at) values (?,?,?,'hash','ACTIVE',true,false,1,now(),now())", id, "$role-$id@example.test", phone)
        jdbc.update("insert into account_roles(account_id,role) values (?,?)", id, role.name)
        return id
    }

    private fun createRequest(customer: UUID, category: UUID, type: String, provider: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into service_requests(id,customer_id,request_type,status,category_id,provider_id,description,general_area,exact_location,preferred_date,created_at,updated_at) values (?,?,?,'OPEN',?,?,?,'Bole','Private apartment 42',?,now(),now())",
            id, customer, type, category, provider, "Repair service needed for booking confirmation", LocalDate.now(ADDIS).plusDays(7))
        return id
    }

    private fun createProposal(request: UUID, provider: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into proposals(id,request_id,provider_id,proposed_price,expected_timeline,provider_message,status,created_at,updated_at) values (?,?,?,1200.00,'Two hours','Professional service proposal','PENDING',now(),now())", id, request, provider)
        return id
    }

    private fun intervalFor(provider: UUID, startHour: Int, endHour: Int): Pair<OffsetDateTime, OffsetDateTime> {
        val date = LocalDate.now(ADDIS).plusDays(7)
        return date.atTime(startHour, 0).atZone(ADDIS).toOffsetDateTime() to date.atTime(endHour, 0).atZone(ADDIS).toOffsetDateTime()
    }

    private fun accept(customer: UUID, proposal: UUID, interval: Pair<OffsetDateTime, OffsetDateTime>) = mockMvc.post("/api/v1/customers/me/proposals/$proposal/accept") {
        bearer(access(customer)); contentType = MediaType.APPLICATION_JSON
        content = """{"scheduledStart":"${interval.first}","scheduledEnd":"${interval.second}"}"""
    }.andExpect { status { isOk() } }.andReturn().response.contentAsString

    private fun access(id: UUID) = tokens.issue(accounts.findById(id)!!, UUID.randomUUID(), Instant.now()).value
    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) { header("Authorization", "Bearer $token") }

    companion object {
        private val ADDIS = ZoneId.of("Africa/Addis_Ababa")
        @Container @ServiceConnection @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
