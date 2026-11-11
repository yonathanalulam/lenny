package com.skilledservices.platform.communication.infrastructure.query

import com.skilledservices.platform.communication.application.MarketplaceRelationship
import com.skilledservices.platform.communication.application.MarketplaceRelationshipQuery
import com.skilledservices.platform.communication.domain.RelatedEntityType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class MarketplaceRelationshipJdbcQuery(private val jdbc: JdbcTemplate) : MarketplaceRelationshipQuery {
    override fun forBooking(bookingId: UUID, participantId: UUID): MarketplaceRelationship? =
        jdbc.query(
            """select request_id, id, customer_id, provider_id from bookings
               where id = ? and (customer_id = ? or provider_id = ?)""".trimIndent(),
            { rs, _ -> MarketplaceRelationship(rs.getObject("request_id", UUID::class.java), rs.getObject("id", UUID::class.java),
                rs.getObject("customer_id", UUID::class.java), rs.getObject("provider_id", UUID::class.java)) },
            bookingId, participantId, participantId,
        ).firstOrNull()

    override fun forRequest(requestId: UUID, participantId: UUID, counterpartId: UUID?): MarketplaceRelationship? {
        val request = jdbc.query(
            """select r.customer_id, r.provider_id, b.id as booking_id, b.provider_id as booking_provider_id
               from service_requests r left join bookings b on b.request_id = r.id where r.id = ?""".trimIndent(),
            { rs, _ -> RequestRelationshipRow(rs.getObject("customer_id", UUID::class.java),
                rs.getObject("provider_id", UUID::class.java), rs.getObject("booking_id", UUID::class.java),
                rs.getObject("booking_provider_id", UUID::class.java)) }, requestId,
        ).firstOrNull() ?: return null
        request.bookingProviderId?.let { provider ->
            if (participantId == request.customerId || participantId == provider) return MarketplaceRelationship(requestId, request.bookingId, request.customerId, provider)
            return null
        }
        val provider = if (participantId == request.customerId) counterpartId ?: request.directProviderId else participantId
        if (provider == null || provider == request.customerId) return null
        val permitted = request.directProviderId == provider || jdbc.queryForObject(
            "select exists(select 1 from proposals where request_id = ? and provider_id = ?)", Boolean::class.java, requestId, provider,
        ) == true
        return if (permitted && (participantId == request.customerId || participantId == provider))
            MarketplaceRelationship(requestId, null, request.customerId, provider) else null
    }

    override fun hasRelationship(firstId: UUID, secondId: UUID): Boolean = jdbc.queryForObject(
        """select exists(
             select 1 from bookings b where (b.customer_id = ? and b.provider_id = ?) or (b.customer_id = ? and b.provider_id = ?)
             union all
             select 1 from service_requests r where r.provider_id is not null and
               ((r.customer_id = ? and r.provider_id = ?) or (r.customer_id = ? and r.provider_id = ?))
             union all
             select 1 from proposals p join service_requests r on r.id = p.request_id where
               ((r.customer_id = ? and p.provider_id = ?) or (r.customer_id = ? and p.provider_id = ?))
           )""".trimIndent(), Boolean::class.java,
        firstId, secondId, secondId, firstId,
        firstId, secondId, secondId, firstId,
        firstId, secondId, secondId, firstId,
    ) == true

    override fun contextMatches(type: RelatedEntityType, id: UUID, firstId: UUID, secondId: UUID): Boolean = when (type) {
        RelatedEntityType.USER -> id == secondId && hasRelationship(firstId, secondId)
        RelatedEntityType.BOOKING -> forBooking(id, firstId)?.let { participantsMatch(it, firstId, secondId) } == true
        RelatedEntityType.REQUEST -> forRequest(id, firstId, secondId)?.let { participantsMatch(it, firstId, secondId) } == true
        RelatedEntityType.CONVERSATION -> jdbc.queryForObject(
            """select exists(select 1 from conversations where id = ? and
               ((customer_id = ? and provider_id = ?) or (customer_id = ? and provider_id = ?)))""".trimIndent(),
            Boolean::class.java, id, firstId, secondId, secondId, firstId,
        ) == true
    }

    private fun participantsMatch(relationship: MarketplaceRelationship, firstId: UUID, secondId: UUID) =
        (relationship.customerId == firstId && relationship.providerId == secondId) ||
            (relationship.customerId == secondId && relationship.providerId == firstId)

    private data class RequestRelationshipRow(
        val customerId: UUID,
        val directProviderId: UUID?,
        val bookingId: UUID?,
        val bookingProviderId: UUID?,
    )
}
