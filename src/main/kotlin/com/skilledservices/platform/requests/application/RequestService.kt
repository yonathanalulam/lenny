package com.skilledservices.platform.requests.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.marketplace.domain.CategoryInactiveException
import com.skilledservices.platform.marketplace.domain.CategoryNotFoundException
import com.skilledservices.platform.marketplace.domain.ProviderCategoryStore
import com.skilledservices.platform.marketplace.domain.ServiceCategoryStore
import com.skilledservices.platform.profiles.application.CustomerEligibilityService
import com.skilledservices.platform.profiles.application.ProviderEligibilityService
import com.skilledservices.platform.requests.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import com.skilledservices.platform.shared.storage.ProtectedObject
import com.skilledservices.platform.shared.storage.ProtectedObjectStorage
import com.skilledservices.platform.shared.storage.ProtectedObjectStore
import com.skilledservices.platform.shared.storage.ProtectedUploadPolicy
import com.skilledservices.platform.shared.events.PlatformNotification
import com.skilledservices.platform.shared.events.PlatformNotificationPort
import com.skilledservices.platform.shared.events.MarketplaceInteractionPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class CreateRequestCommand(
    val type: ServiceRequestType,
    val categoryId: UUID,
    val providerId: UUID?,
    val description: String,
    val generalArea: String,
    val exactLocation: String,
    val preferredDate: LocalDate,
    val preferredStartTime: LocalTime?,
    val preferredEndTime: LocalTime?,
    val urgency: RequestUrgency?,
    val budgetMin: BigDecimal?,
    val budgetMax: BigDecimal?,
    val specialInstructions: String?,
)

data class UpdateRequestCommand(
    val description: String?, val generalArea: String?, val exactLocation: String?, val preferredDate: LocalDate?,
    val preferredStartTime: LocalTime?, val preferredEndTime: LocalTime?, val urgency: RequestUrgency?,
    val budgetMin: BigDecimal?, val budgetMax: BigDecimal?, val specialInstructions: String?,
)

data class AttachmentUpload(val originalFilename: String, val contentType: String, val content: ByteArray)
data class RequestDetails(val request: ServiceRequest, val invitation: DirectInvitation?, val attachments: List<RequestAttachment>)
data class ProviderInvitationDetails(val invitation: DirectInvitation, val request: ServiceRequest, val attachments: List<RequestAttachment>)
data class RequestAttachmentDownload(val originalFilename: String, val contentType: String, val content: ByteArray)

@Service
class RequestService(
    private val requests: ServiceRequestStore,
    private val invitations: InvitationStore,
    private val attachments: RequestAttachmentStore,
    private val categories: ServiceCategoryStore,
    private val providerCategories: ProviderCategoryStore,
    private val customerEligibility: CustomerEligibilityService,
    private val providerEligibility: ProviderEligibilityService,
    private val identity: IdentityAccountQuery,
    private val protectedObjects: ProtectedObjectStore,
    private val storage: ProtectedObjectStorage,
    private val uploadPolicy: ProtectedUploadPolicy,
    private val activities: MarketplaceActivityStore,
    private val providerRequestEligibility: ProviderRequestEligibility,
    private val notifications: PlatformNotificationPort,
    private val interactionPolicy: MarketplaceInteractionPolicy,
    private val clock: Clock,
) {
    @Transactional
    fun create(customerId: UUID, command: CreateRequestCommand): RequestDetails {
        requireCustomer(customerId)
        if (!customerEligibility.canCreateMarketplaceRequest(customerId)) throw CustomerNotEligibleException()
        validate(command)
        val category = categories.findById(command.categoryId) ?: throw CategoryNotFoundException()
        if (!category.active) throw CategoryInactiveException()
        if (command.type == ServiceRequestType.DIRECT_INVITATION) {
            interactionPolicy.requireAllowed(customerId, command.providerId!!)
            validateTargetProvider(command.providerId, category.id)
        }
        val now = clock.instant()
        val request = requests.save(
            ServiceRequest(
                UUID.randomUUID(), customerId, command.type,
                if (command.type == ServiceRequestType.DIRECT_INVITATION) ServiceRequestStatus.OPEN else ServiceRequestStatus.DRAFT,
                command.categoryId, command.providerId,
                command.description.trim(), command.generalArea.trim(), command.exactLocation.trim(), command.preferredDate,
                command.preferredStartTime, command.preferredEndTime, command.urgency, command.budgetMin, command.budgetMax,
                command.specialInstructions?.trim()?.takeIf { it.isNotEmpty() }, now, now, null,
            ),
        )
        val invitation = if (command.type == ServiceRequestType.DIRECT_INVITATION) {
            invitations.save(DirectInvitation(UUID.randomUUID(), request.id, command.providerId!!, InvitationStatus.PENDING, now, now)).also {
                activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "INVITATION", it.id, "INVITATION_SENT", customerId, now))
                notifications.publish(PlatformNotification(it.providerId, "INVITATION_RECEIVED", "Invitation received",
                    "A customer invited you to a service request.", "INVITATION", it.id))
            }
        } else null
        return RequestDetails(request, invitation, emptyList())
    }

    @Transactional(readOnly = true)
    fun listForCustomer(customerId: UUID, page: Int, size: Int): PageResponse<RequestDetails> {
        requireCustomer(customerId)
        requirePage(page, size)
        val all = requests.findByCustomerId(customerId).map(::details)
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(all.subList(from, to), page, size, all.size.toLong(), pages, page + 1 < pages)
    }

    @Transactional(readOnly = true)
    fun getForCustomer(customerId: UUID, requestId: UUID): RequestDetails {
        requireCustomer(customerId)
        val request = requests.findById(requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw RequestNotOwnedException()
        return details(request)
    }

    @Transactional
    fun update(customerId: UUID, requestId: UUID, command: UpdateRequestCommand): RequestDetails {
        requireCustomer(customerId)
        val existing = requests.findByIdForUpdate(requestId) ?: throw RequestNotFoundException()
        if (existing.customerId != customerId) throw RequestNotOwnedException()
        if (existing.status !in setOf(ServiceRequestStatus.DRAFT, ServiceRequestStatus.AWAITING_POSTING_FEE)) {
            throw InvalidRequestStateException("Only draft or awaiting-fee requests can be edited")
        }
        val updatedCommand = CreateRequestCommand(
            existing.type, existing.categoryId, existing.providerId,
            command.description ?: existing.description, command.generalArea ?: existing.generalArea,
            command.exactLocation ?: existing.exactLocation, command.preferredDate ?: existing.preferredDate,
            command.preferredStartTime ?: existing.preferredStartTime, command.preferredEndTime ?: existing.preferredEndTime,
            command.urgency ?: existing.urgency, command.budgetMin ?: existing.budgetMin,
            command.budgetMax ?: existing.budgetMax, command.specialInstructions ?: existing.specialInstructions,
        )
        validate(updatedCommand)
        val now = clock.instant()
        val saved = requests.save(
            existing.copy(
                status = if (existing.status == ServiceRequestStatus.AWAITING_POSTING_FEE) ServiceRequestStatus.DRAFT else existing.status,
                description = updatedCommand.description.trim(), generalArea = updatedCommand.generalArea.trim(),
                exactLocation = updatedCommand.exactLocation.trim(), preferredDate = updatedCommand.preferredDate,
                preferredStartTime = updatedCommand.preferredStartTime, preferredEndTime = updatedCommand.preferredEndTime,
                urgency = updatedCommand.urgency, budgetMin = updatedCommand.budgetMin, budgetMax = updatedCommand.budgetMax,
                specialInstructions = updatedCommand.specialInstructions?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = now,
            ),
        )
        return details(saved)
    }

    @Transactional
    fun requestPublication(customerId: UUID, requestId: UUID): RequestDetails {
        val request = ownedForUpdate(customerId, requestId)
        if (request.type != ServiceRequestType.JOB_POSTING) throw InvalidRequestTypeException("Only Job Postings require publication")
        if (request.status != ServiceRequestStatus.DRAFT) throw InvalidRequestStateException("Only a draft can request publication")
        return details(requests.save(request.copy(status = ServiceRequestStatus.AWAITING_POSTING_FEE, updatedAt = clock.instant())))
    }

    @Transactional
    fun cancel(customerId: UUID, requestId: UUID): RequestDetails {
        val request = ownedForUpdate(customerId, requestId)
        if (request.status == ServiceRequestStatus.CANCELLED || request.status == ServiceRequestStatus.PROVIDER_SELECTED ||
            (request.status == ServiceRequestStatus.OPEN && request.type != ServiceRequestType.DIRECT_INVITATION)) {
            throw InvalidRequestStateException("Request cannot be cancelled in its current state")
        }
        val invitation = invitations.findByRequestId(request.id)
        if (request.type == ServiceRequestType.DIRECT_INVITATION && invitation?.status != InvitationStatus.PENDING) {
            throw InvalidRequestStateException("Only a pending Direct Invitation can be withdrawn")
        }
        val now = clock.instant()
        val saved = requests.save(request.copy(status = ServiceRequestStatus.CANCELLED, cancelledAt = now, updatedAt = now))
        invitation?.let {
            invitations.save(it.copy(status = InvitationStatus.WITHDRAWN, updatedAt = now))
            activities.save(MarketplaceActivityEvent(UUID.randomUUID(), "INVITATION", it.id, "INVITATION_WITHDRAWN", customerId, now))
            notifications.publish(PlatformNotification(it.providerId, "INVITATION_WITHDRAWN", "Invitation withdrawn",
                "A customer withdrew a service invitation.", "INVITATION", it.id))
        }
        return details(saved)
    }

    @Transactional
    fun uploadAttachment(customerId: UUID, requestId: UUID, upload: AttachmentUpload): RequestAttachment {
        val request = ownedForUpdate(customerId, requestId)
        if (request.status == ServiceRequestStatus.CANCELLED) throw InvalidRequestStateException("Cannot attach files to a cancelled request")
        validateUpload(upload)
        val now = clock.instant()
        val objectId = UUID.randomUUID()
        val storageKey = "requests/${request.id}/$objectId"
        storage.put(storageKey, upload.content)
        registerRollbackCleanup(storageKey)
        protectedObjects.save(ProtectedObject(objectId, storageKey, customerId, now))
        return attachments.save(RequestAttachment(UUID.randomUUID(), request.id, safeFilename(upload.originalFilename), upload.contentType.lowercase(), upload.content.size.toLong(), objectId, now))
    }

    @Transactional(readOnly = true)
    fun listForProvider(providerId: UUID, page: Int, size: Int): PageResponse<ProviderInvitationDetails> {
        requireProvider(providerId)
        requirePage(page, size)
        val all = invitations.findByProviderId(providerId).map { invitation ->
            val request = requests.findById(invitation.requestId) ?: throw RequestNotFoundException()
            ProviderInvitationDetails(invitation, request, attachments.findByRequestId(request.id))
        }
        val from = (page * size).coerceAtMost(all.size)
        val to = (from + size).coerceAtMost(all.size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(all.subList(from, to), page, size, all.size.toLong(), pages, page + 1 < pages)
    }

    @Transactional(readOnly = true)
    fun getForProvider(providerId: UUID, invitationId: UUID): ProviderInvitationDetails {
        requireProvider(providerId)
        val invitation = invitations.findById(invitationId) ?: throw InvitationNotFoundException()
        if (invitation.providerId != providerId) throw InvitationNotFoundException()
        val request = requests.findById(invitation.requestId) ?: throw RequestNotFoundException()
        return ProviderInvitationDetails(invitation, request, attachments.findByRequestId(request.id))
    }

    @Transactional(readOnly = true)
    fun downloadAttachment(requesterId: UUID, attachmentId: UUID): RequestAttachmentDownload {
        val attachment = attachments.findById(attachmentId) ?: throw RequestNotFoundException()
        val request = requests.findById(attachment.requestId) ?: throw RequestNotFoundException()
        val invitedProvider = invitations.findByRequestId(request.id)?.providerId == requesterId
        val eligibleJobProvider = request.type == ServiceRequestType.JOB_POSTING && request.status == ServiceRequestStatus.OPEN &&
            providerRequestEligibility.isEligibleFor(requesterId, request)
        val admin = identity.hasRole(requesterId, AccountRole.ADMIN)
        if (request.customerId != requesterId && !invitedProvider && !eligibleJobProvider && !admin) throw AttachmentAccessDeniedException()
        val protectedObject = protectedObjects.findById(attachment.protectedObjectId) ?: throw RequestNotFoundException()
        return RequestAttachmentDownload(attachment.originalFilename, attachment.contentType, storage.get(protectedObject.storageKey))
    }

    private fun ownedForUpdate(customerId: UUID, requestId: UUID): ServiceRequest {
        requireCustomer(customerId)
        val request = requests.findByIdForUpdate(requestId) ?: throw RequestNotFoundException()
        if (request.customerId != customerId) throw RequestNotOwnedException()
        return request
    }

    private fun details(request: ServiceRequest) = RequestDetails(request, invitations.findByRequestId(request.id), attachments.findByRequestId(request.id))
    private fun requireCustomer(id: UUID) { if (!identity.hasRole(id, AccountRole.CUSTOMER)) throw RequestNotOwnedException() }
    private fun requireProvider(id: UUID) { if (!identity.hasRole(id, AccountRole.SERVICE_PROVIDER)) throw InvitationNotFoundException() }
    private fun requirePage(page: Int, size: Int) { if (page < 0 || size !in 1..100) throw InvalidRequestException("page must be non-negative and size must be between 1 and 100") }

    private fun validate(command: CreateRequestCommand) {
        if (command.type == ServiceRequestType.DIRECT_INVITATION && command.providerId == null) throw InvalidRequestTypeException("Direct Invitation requires providerId")
        if (command.type == ServiceRequestType.JOB_POSTING && command.providerId != null) throw InvalidRequestTypeException("Job Posting must not specify providerId")
        cleanRequired(command.description, "description", 10, 4000)
        cleanRequired(command.generalArea, "generalArea", 2, 160)
        cleanRequired(command.exactLocation, "exactLocation", 5, 1000)
        if (command.preferredDate.isBefore(LocalDate.now(clock.withZone(ZoneId.of("Africa/Addis_Ababa"))))) throw InvalidServiceDateException()
        if ((command.preferredStartTime == null) != (command.preferredEndTime == null) || (command.preferredStartTime != null && command.preferredStartTime >= command.preferredEndTime)) throw InvalidRequestException("Preferred start time must be before end time")
        if (command.budgetMin?.signum() == -1 || command.budgetMax?.signum() == -1 || (command.budgetMin != null && command.budgetMax != null && command.budgetMin > command.budgetMax)) throw InvalidBudgetRangeException()
        if (command.budgetMin?.scale()?.let { it > 2 } == true || command.budgetMax?.scale()?.let { it > 2 } == true) throw InvalidBudgetRangeException()
        command.specialInstructions?.let { if (it.length > 4000) throw InvalidRequestException("specialInstructions must not exceed 4000 characters") }
    }

    private fun validateTargetProvider(providerId: UUID, categoryId: UUID) {
        if (identity.accountStatus(providerId) != AccountStatus.ACTIVE || !providerEligibility.isMarketplaceEligible(providerId)) throw ProviderNotEligibleException()
        val requested = categories.findById(categoryId) ?: throw CategoryNotFoundException()
        val offered = providerCategories.findByProviderId(providerId).map { it.categoryId }.toSet()
        val acceptable = if (requested.parentId == null) categories.findAll().filter { it.id == categoryId || it.parentId == categoryId }.map { it.id }.toSet() else setOf(categoryId)
        if (offered.intersect(acceptable).isEmpty()) throw ProviderNotEligibleException()
    }

    private fun validateUpload(upload: AttachmentUpload) {
        if (upload.content.isEmpty()) throw InvalidRequestException("Attachment cannot be empty")
        if (upload.content.size.toLong() > uploadPolicy.maxFileSizeBytes) throw RequestAttachmentTooLargeException(uploadPolicy.maxFileSizeBytes)
        if (upload.contentType.lowercase() !in uploadPolicy.allowedContentTypes.map(String::lowercase)) throw UnsupportedRequestAttachmentTypeException(upload.contentType)
        safeFilename(upload.originalFilename)
    }

    private fun safeFilename(filename: String): String = runCatching { Path.of(filename).fileName.toString() }.getOrDefault("").trim().also {
        if (it.isEmpty() || it.length > 255 || it.contains('\u0000')) throw InvalidRequestException("Attachment filename is invalid")
    }
    private fun cleanRequired(value: String, field: String, min: Int, max: Int) { if (value.trim().length !in min..max) throw InvalidRequestException("$field must contain between $min and $max characters") }
    private fun registerRollbackCleanup(storageKey: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) { if (status == TransactionSynchronization.STATUS_ROLLED_BACK) runCatching { storage.delete(storageKey) } }
        })
    }
}
