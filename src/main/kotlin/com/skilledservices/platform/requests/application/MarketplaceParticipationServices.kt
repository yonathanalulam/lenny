package com.skilledservices.platform.requests.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.marketplace.domain.ProviderCategoryStore
import com.skilledservices.platform.marketplace.domain.ServiceCategoryStore
import com.skilledservices.platform.profiles.application.DiscoverableProviderProfile
import com.skilledservices.platform.profiles.application.ProviderDiscoveryProfileQuery
import com.skilledservices.platform.profiles.application.ProviderEligibilityService
import com.skilledservices.platform.requests.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

@Service
class ProviderRequestEligibility(
    private val eligibility: ProviderEligibilityService,
    private val identity: IdentityAccountQuery,
    private val assignments: ProviderCategoryStore,
    private val categories: ServiceCategoryStore,
    private val profiles: ProviderDiscoveryProfileQuery,
) {
    fun requireMarketplace(providerId: UUID) {
        if (identity.accountStatus(providerId) != AccountStatus.ACTIVE || !eligibility.isMarketplaceEligible(providerId)) throw ProviderNotEligibleException()
    }

    fun isEligibleFor(providerId: UUID, request: ServiceRequest): Boolean {
        if (runCatching { requireMarketplace(providerId) }.isFailure) return false
        val requested = categories.findById(request.categoryId)?.takeIf { it.active } ?: return false
        val offered = assignments.findByProviderId(providerId).mapNotNull { categories.findById(it.categoryId) }.filter { it.active }
        val categoryMatch = offered.any { it.id == requested.id || (requested.parentId == null && it.parentId == requested.id) }
        if (!categoryMatch) return false
        val area = profiles.findByProviderId(providerId)?.serviceArea?.trim()?.lowercase() ?: return false
        val requestedArea = request.generalArea.trim().lowercase()
        return area.contains("addis ababa") || area.contains(requestedArea) || requestedArea.contains(area)
    }

    fun profile(providerId: UUID): DiscoverableProviderProfile? = profiles.findByProviderId(providerId)
}

data class ProviderJobDetails(val request: ServiceRequest, val attachments: List<RequestAttachment>)

@Service
class ProviderJobService(
    private val requests: ServiceRequestStore,
    private val attachments: RequestAttachmentStore,
    private val providerEligibility: ProviderRequestEligibility,
) {
    @Transactional(readOnly = true)
    fun list(providerId: UUID, page: Int, size: Int): PageResponse<ProviderJobDetails> {
        providerEligibility.requireMarketplace(providerId)
        requirePage(page, size)
        val all = requests.findOpenJobPostings().filter { providerEligibility.isEligibleFor(providerId, it) }
            .map { ProviderJobDetails(it, attachments.findByRequestId(it.id)) }
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(all.subList(from, to), page, size, all.size.toLong(), pages, page + 1 < pages)
    }

    @Transactional(readOnly = true)
    fun get(providerId: UUID, requestId: UUID): ProviderJobDetails {
        providerEligibility.requireMarketplace(providerId)
        val request = requests.findById(requestId) ?: throw JobNotOpenException()
        if (request.type != ServiceRequestType.JOB_POSTING || request.status != ServiceRequestStatus.OPEN) throw JobNotOpenException()
        if (!providerEligibility.isEligibleFor(providerId, request)) throw JobNotEligibleException()
        return ProviderJobDetails(request, attachments.findByRequestId(request.id))
    }

    private fun requirePage(page: Int, size: Int) { if (page < 0 || size !in 1..100) throw InvalidRequestException("page must be non-negative and size must be between 1 and 100") }
}

@Service
class InvitationResponseService(
    private val invitations: InvitationStore,
    private val requests: ServiceRequestStore,
    private val providerEligibility: ProviderRequestEligibility,
    private val activities: MarketplaceActivityStore,
    private val clock: Clock,
) {
    @Transactional
    fun accept(providerId: UUID, invitationId: UUID) = respond(providerId, invitationId, InvitationStatus.ACCEPTED)

    @Transactional
    fun decline(providerId: UUID, invitationId: UUID) = respond(providerId, invitationId, InvitationStatus.DECLINED)

    private fun respond(providerId: UUID, invitationId: UUID, target: InvitationStatus): DirectInvitation {
        providerEligibility.requireMarketplace(providerId)
        val invitation = invitations.findByIdForUpdate(invitationId) ?: throw InvitationNotFoundException()
        if (invitation.providerId != providerId) throw InvitationNotOwnedException()
        if (invitation.status == target) return invitation
        if (invitation.status != InvitationStatus.PENDING) throw InvitationNotPendingException()
        val request = requests.findById(invitation.requestId) ?: throw RequestNotFoundException()
        if (request.status != ServiceRequestStatus.OPEN) throw InvitationNotPendingException()
        val now = clock.instant()
        val saved = invitations.save(invitation.copy(status = target, updatedAt = now))
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "INVITATION", saved.id,
            if (target == InvitationStatus.ACCEPTED) "INVITATION_ACCEPTED" else "INVITATION_DECLINED", providerId, now))
        return saved
    }
}

data class SubmitProposalCommand(
    val proposedPrice: BigDecimal,
    val expectedTimeline: String,
    val providerMessage: String,
    val conditions: String?,
)

data class CustomerProposalDetails(val proposal: Proposal, val provider: DiscoverableProviderProfile)

@Service
class ProposalService(
    private val proposals: ProposalStore,
    private val requests: ServiceRequestStore,
    private val invitations: InvitationStore,
    private val providerEligibility: ProviderRequestEligibility,
    private val identity: IdentityAccountQuery,
    private val activities: MarketplaceActivityStore,
    private val clock: Clock,
) {
    @Transactional
    fun submit(providerId: UUID, requestId: UUID, command: SubmitProposalCommand): Proposal {
        providerEligibility.requireMarketplace(providerId)
        val request = requests.findById(requestId) ?: throw RequestNotFoundException()
        if (request.status != ServiceRequestStatus.OPEN) throw JobNotOpenException()
        when (request.type) {
            ServiceRequestType.DIRECT_INVITATION -> {
                val invitation = invitations.findByRequestId(request.id) ?: throw InvitationNotFoundException()
                if (invitation.providerId != providerId) throw InvitationNotOwnedException()
                if (invitation.status != InvitationStatus.ACCEPTED) throw InvitationNotPendingException()
            }
            ServiceRequestType.JOB_POSTING -> if (!providerEligibility.isEligibleFor(providerId, request)) throw JobNotEligibleException()
        }
        if (proposals.findByRequestAndProvider(requestId, providerId) != null) throw ProposalAlreadyExistsException()
        val price = runCatching { command.proposedPrice.setScale(2, RoundingMode.UNNECESSARY) }.getOrElse { throw InvalidBudgetRangeException() }
        if (price.signum() <= 0) throw InvalidBudgetRangeException()
        val timeline = clean(command.expectedTimeline, "expectedTimeline", 2, 500)
        val message = clean(command.providerMessage, "providerMessage", 10, 4000)
        val conditions = command.conditions?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 2000) throw InvalidRequestException("conditions must not exceed 2000 characters") }
        val now = clock.instant()
        val proposal = try {
            proposals.save(Proposal(UUID.randomUUID(), requestId, providerId, price, timeline, message, conditions,
                ProposalStatus.PENDING, now, now, null, null))
        } catch (_: DataIntegrityViolationException) { throw ProposalAlreadyExistsException() }
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "PROPOSAL", proposal.id, "PROPOSAL_SUBMITTED", providerId, now))
        return proposal
    }

    @Transactional
    fun withdraw(providerId: UUID, proposalId: UUID): Proposal {
        val proposal = proposals.findByIdForUpdate(proposalId) ?: throw ProposalNotFoundException()
        if (proposal.providerId != providerId) throw ProposalNotOwnedException()
        if (proposal.status != ProposalStatus.PENDING) throw ProposalNotPendingException()
        val now = clock.instant()
        val saved = proposals.save(proposal.copy(status = ProposalStatus.WITHDRAWN, updatedAt = now, withdrawnAt = now))
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "PROPOSAL", saved.id, "PROPOSAL_WITHDRAWN", providerId, now))
        return saved
    }

    @Transactional(readOnly = true)
    fun listForCustomer(customerId: UUID, requestId: UUID): List<CustomerProposalDetails> {
        requireCustomer(customerId)
        val request = requests.findById(requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw RequestNotOwnedException()
        return proposals.findByRequestId(requestId).map { customerDetails(it) }
    }

    @Transactional(readOnly = true)
    fun getForCustomer(customerId: UUID, proposalId: UUID): CustomerProposalDetails {
        requireCustomer(customerId)
        val proposal = proposals.findById(proposalId) ?: throw ProposalNotFoundException()
        val request = requests.findById(proposal.requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw ProposalNotOwnedException()
        return customerDetails(proposal)
    }

    @Transactional
    fun reject(customerId: UUID, proposalId: UUID): Proposal {
        requireCustomer(customerId)
        val proposal = proposals.findByIdForUpdate(proposalId) ?: throw ProposalNotFoundException()
        val request = requests.findById(proposal.requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw ProposalNotOwnedException()
        if (proposal.status == ProposalStatus.REJECTED) return proposal
        if (proposal.status != ProposalStatus.PENDING) throw ProposalNotPendingException()
        val now = clock.instant()
        val saved = proposals.save(proposal.copy(status = ProposalStatus.REJECTED, updatedAt = now, rejectedAt = now))
        activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "PROPOSAL", saved.id, "PROPOSAL_REJECTED", customerId, now))
        return saved
    }

    private fun customerDetails(proposal: Proposal) = CustomerProposalDetails(proposal,
        providerEligibility.profile(proposal.providerId) ?: throw ProposalNotFoundException())
    private fun requireCustomer(id: UUID) {
        if (!identity.hasRole(id, com.skilledservices.platform.identity.domain.AccountRole.CUSTOMER)) throw ProposalNotOwnedException()
    }
    private fun clean(value: String, field: String, min: Int, max: Int) = value.trim().also {
        if (it.length !in min..max) throw InvalidRequestException("$field must contain between $min and $max characters")
    }
}
