package com.skilledservices.platform.requests.application

import com.skilledservices.platform.booking.domain.*
import com.skilledservices.platform.requests.domain.*
import com.skilledservices.platform.shared.events.PlatformNotification
import com.skilledservices.platform.shared.events.PlatformNotificationPort
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RequestsBookingSelectionAdapter(
    private val proposals: ProposalStore,
    private val requests: ServiceRequestStore,
    private val providerEligibility: ProviderRequestEligibility,
    private val activities: MarketplaceActivityStore,
    private val notifications: PlatformNotificationPort,
) : BookingSelectionPort {
    override fun lockForAcceptance(customerId: UUID, proposalId: UUID): BookingSelection {
        val proposal = proposals.findByIdForUpdate(proposalId) ?: throw ProposalNotAcceptableException()
        val request = requests.findByIdForUpdate(proposal.requestId) ?: throw ProposalNotAcceptableException()
        if (request.customerId != customerId) throw BookingNotOwnedException()
        if (request.status !in setOf(ServiceRequestStatus.OPEN, ServiceRequestStatus.PROVIDER_SELECTED)) throw ProposalNotAcceptableException()
        if (proposal.status !in setOf(ProposalStatus.PENDING, ProposalStatus.ACCEPTED)) throw ProposalNotAcceptableException()
        if (!providerEligibility.isEligibleFor(proposal.providerId, request)) throw ProviderNotEligibleException()
        return BookingSelection(proposal.id, request.id, request.customerId, proposal.providerId, proposal.proposedPrice, request.status.name, proposal.status.name)
    }

    override fun finalizeSelection(selection: BookingSelection, bookingId: UUID, occurredAt: Instant) {
        val selected = proposals.findByIdForUpdate(selection.proposalId) ?: throw ProposalNotAcceptableException()
        proposals.save(selected.copy(status = ProposalStatus.ACCEPTED, updatedAt = occurredAt))
        proposals.findByRequestId(selection.requestId).filter { it.id != selected.id && it.status == ProposalStatus.PENDING }.forEach {
            proposals.save(it.copy(status = ProposalStatus.REJECTED, updatedAt = occurredAt, rejectedAt = occurredAt))
            activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "PROPOSAL", it.id, "PROPOSAL_REJECTED_BY_SELECTION", selection.customerId, occurredAt))
            notifications.publish(PlatformNotification(it.providerId, "PROPOSAL_REJECTED", "Proposal not selected",
                "The customer selected another proposal.", "PROPOSAL", it.id))
        }
        val request = requests.findByIdForUpdate(selection.requestId) ?: throw ProposalNotAcceptableException()
        requests.save(request.copy(status = ServiceRequestStatus.PROVIDER_SELECTED, providerId = selection.providerId, updatedAt = occurredAt))
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "PROPOSAL", selected.id, "PROPOSAL_ACCEPTED", selection.customerId, occurredAt))
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "BOOKING", bookingId, "BOOKING_CONFIRMED", selection.customerId, occurredAt))
    }

    override fun requestSnapshot(requestId: UUID): BookingRequestSnapshot {
        val request = requests.findById(requestId) ?: throw BookingNotFoundException()
        return BookingRequestSnapshot(request.id, request.generalArea, request.exactLocation, request.description)
    }
}
