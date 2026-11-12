package com.skilledservices.platform.administration.application

import com.skilledservices.platform.shared.api.PageResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class AdminActivityEvent(
    val id: UUID,
    val actorAccountId: UUID?,
    val eventType: String,
    val subjectType: String,
    val subjectId: UUID,
    val occurredAt: Instant,
)

data class AdminDashboardMetrics(
    val totalCustomers: Long,
    val totalProviders: Long,
    val verificationCounts: Map<String, Long>,
    val activeCategories: Long,
    val requestCounts: Map<String, Long>,
    val bookingCounts: Map<String, Long>,
    val completedServices: Long,
    val cancellationCount: Long,
    val openReports: Long,
    val openSupportCases: Long,
    val averageProviderRating: Double?,
    val postingFeeCounts: Map<String, Long>,
    val recentActivity: List<AdminActivityEvent>,
)

@Service
class AdministrationReportingService(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun dashboard() = AdminDashboardMetrics(
        totalCustomers = count("select count(distinct account_id) from account_roles where role='CUSTOMER'"),
        totalProviders = count("select count(distinct account_id) from account_roles where role='SERVICE_PROVIDER'"),
        verificationCounts = grouped("select status, count(*) as count from marketplace_verifications group by status"),
        activeCategories = count("select count(*) from service_categories where active=true"),
        requestCounts = grouped("select status, count(*) as count from service_requests group by status"),
        bookingCounts = grouped("select status, count(*) as count from bookings group by status"),
        completedServices = count("select count(*) from bookings where status='COMPLETED'"),
        cancellationCount = count("select count(*) from bookings where status='CANCELLED'"),
        openReports = count("select count(*) from user_reports where status in ('OPEN','IN_REVIEW')"),
        openSupportCases = count("select count(*) from support_cases where status in ('OPEN','IN_REVIEW')"),
        averageProviderRating = jdbc.queryForObject(
            "select avg(rating)::double precision from booking_feedback where direction='CUSTOMER_TO_PROVIDER' and visible=true",
            Double::class.java,
        ),
        postingFeeCounts = grouped("select status, count(*) as count from posting_fee_transactions group by status"),
        recentActivity = audit(null, null, 0, 10).items,
    )

    @Transactional(readOnly = true)
    fun audit(actorId: UUID?, eventType: String?, page: Int, size: Int): PageResponse<AdminActivityEvent> {
        require(page >= 0 && size in 1..100) { "page must be non-negative and size must be between 1 and 100" }
        val conditions = mutableListOf<String>()
        val parameters = mutableMapOf<String, Any>("limit" to size, "offset" to page * size)
        if (actorId != null) { conditions += "actor_account_id = :actorId"; parameters["actorId"] = actorId }
        if (!eventType.isNullOrBlank()) { conditions += "event_type = :eventType"; parameters["eventType"] = eventType.trim() }
        val where = if (conditions.isEmpty()) "" else " where ${conditions.joinToString(" and ")}"
        val union = """select id, actor_account_id, event_type, subject_type, subject_id, occurred_at from platform_audit_events
            union all
            select id, actor_id as actor_account_id, event_type, aggregate_type as subject_type, aggregate_id as subject_id, occurred_at from marketplace_activity_events""".trimIndent()
        val total = namedJdbc.queryForObject("select count(*) from ($union) events$where", parameters, Long::class.java) ?: 0L
        val items = namedJdbc.query("select * from ($union) events$where order by occurred_at desc, id desc limit :limit offset :offset", parameters) { rs, _ ->
            AdminActivityEvent(rs.getObject("id", UUID::class.java), rs.getObject("actor_account_id", UUID::class.java),
                rs.getString("event_type"), rs.getString("subject_type"), rs.getObject("subject_id", UUID::class.java),
                rs.getTimestamp("occurred_at").toInstant())
        }
        val pages = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(items, page, size, total, pages, page + 1 < pages)
    }

    private fun count(sql: String) = jdbc.queryForObject(sql, Long::class.java) ?: 0L
    private fun grouped(sql: String) = jdbc.query(sql) { rs, _ -> rs.getString(1) to rs.getLong(2) }.toMap()
}
