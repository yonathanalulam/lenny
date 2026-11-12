package com.skilledservices.platform.communication.application

import com.skilledservices.platform.booking.application.BookingDisputePort
import com.skilledservices.platform.communication.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import com.skilledservices.platform.shared.pagination.PageSlice
import com.skilledservices.platform.shared.events.PlatformNotification
import com.skilledservices.platform.shared.events.PlatformNotificationPort
import com.skilledservices.platform.shared.events.MarketplaceInteractionPolicy
import com.skilledservices.platform.shared.events.PlatformAuditCommand
import com.skilledservices.platform.shared.events.PlatformAuditPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class MarketplaceRelationship(
    val requestId: UUID,
    val bookingId: UUID?,
    val customerId: UUID,
    val providerId: UUID,
)

interface MarketplaceRelationshipQuery {
    fun forRequest(requestId: UUID, participantId: UUID, counterpartId: UUID? = null): MarketplaceRelationship?
    fun forBooking(bookingId: UUID, participantId: UUID): MarketplaceRelationship?
    fun hasRelationship(firstId: UUID, secondId: UUID): Boolean
    fun contextMatches(type: RelatedEntityType, id: UUID, firstId: UUID, secondId: UUID): Boolean
}

data class CreateConversationCommand(val requestId: UUID?, val bookingId: UUID?, val counterpartAccountId: UUID?)
data class SendMessageCommand(val body: String)
data class CreateBlockCommand(val blockedAccountId: UUID, val reason: BlockReason?)
data class CreateReportCommand(
    val reportedAccountId: UUID,
    val relatedEntityType: RelatedEntityType?,
    val relatedEntityId: UUID?,
    val category: ReportCategory,
    val description: String,
)
data class ReviewReportCommand(val status: ReportStatus, val moderationNote: String?)
data class CreateSupportCaseCommand(
    val bookingId: UUID?,
    val requestId: UUID?,
    val type: SupportCaseType,
    val description: String,
)
data class ReviewSupportCaseCommand(
    val status: SupportCaseStatus,
    val internalNote: String?,
    val resolutionOutcome: String?,
)
data class UpdatePreferencesCommand(
    val preferredLanguage: PreferredLanguage,
    val marketplaceNotificationsEnabled: Boolean,
    val communicationNotificationsEnabled: Boolean,
    val supportNotificationsEnabled: Boolean,
)

@Service
class ConversationService(
    private val conversations: ConversationStore,
    private val messages: MessageStore,
    private val blocks: UserBlockStore,
    private val relationships: MarketplaceRelationshipQuery,
    private val notifications: PlatformNotificationPort,
    private val clock: Clock,
) {
    @Transactional
    fun create(accountId: UUID, command: CreateConversationCommand): Conversation {
        if ((command.requestId == null) == (command.bookingId == null)) throw ConversationRelationshipRequiredException()
        val relationship = command.bookingId?.let { relationships.forBooking(it, accountId) }
            ?: command.requestId?.let { relationships.forRequest(it, accountId, command.counterpartAccountId) }
            ?: throw ConversationRelationshipRequiredException()
        if (blocks.existsEitherDirection(relationship.customerId, relationship.providerId)) throw InteractionBlockedException()
        command.bookingId?.let { conversations.findByBookingId(it) }?.let { return it }
        conversations.findByRequestAndParticipants(relationship.requestId, relationship.customerId, relationship.providerId)?.let { return it }
        val now = clock.instant()
        return try {
            conversations.save(Conversation(UUID.randomUUID(), relationship.requestId, relationship.bookingId,
                relationship.customerId, relationship.providerId, ConversationState.ACTIVE, now, now))
        } catch (_: DataIntegrityViolationException) {
            command.bookingId?.let(conversations::findByBookingId)
                ?: conversations.findByRequestAndParticipants(relationship.requestId, relationship.customerId, relationship.providerId)
                ?: throw ConversationRelationshipRequiredException()
        }
    }

    @Transactional(readOnly = true)
    fun list(accountId: UUID, page: Int, size: Int) = conversations.findByParticipant(accountId, validPage(page), validSize(size)).toPage(page, size)

    @Transactional(readOnly = true)
    fun get(accountId: UUID, conversationId: UUID) = participantConversation(accountId, conversationId)

    @Transactional(readOnly = true)
    fun messages(accountId: UUID, conversationId: UUID, page: Int, size: Int): PageResponse<Message> {
        participantConversation(accountId, conversationId)
        return messages.findByConversation(conversationId, validPage(page), validSize(size)).toPage(page, size)
    }

    @Transactional
    fun send(accountId: UUID, conversationId: UUID, command: SendMessageCommand): Message {
        val body = command.body.trim()
        if (body.isEmpty() || body.length > 4000) throw MessageInvalidException()
        val conversation = conversations.findByIdForUpdate(conversationId) ?: throw ConversationNotFoundException()
        ensureParticipant(accountId, conversation)
        if (conversation.state != ConversationState.ACTIVE) throw ConversationClosedException()
        val recipientId = otherParticipant(accountId, conversation)
        if (blocks.existsEitherDirection(accountId, recipientId)) throw InteractionBlockedException()
        val now = clock.instant()
        val saved = messages.save(Message(UUID.randomUUID(), conversationId, accountId, body, now, null))
        conversations.save(conversation.copy(lastActivityAt = now))
        notifications.publish(PlatformNotification(recipientId, "MESSAGE_RECEIVED", "New message",
            "You received a new marketplace message.", "CONVERSATION", conversationId))
        return saved
    }

    @Transactional
    fun markRead(accountId: UUID, conversationId: UUID): Int {
        participantConversation(accountId, conversationId)
        return messages.markReceivedRead(conversationId, accountId, clock.instant())
    }

    private fun participantConversation(accountId: UUID, conversationId: UUID): Conversation {
        val conversation = conversations.findById(conversationId) ?: throw ConversationNotFoundException()
        ensureParticipant(accountId, conversation)
        return conversation
    }
    private fun ensureParticipant(accountId: UUID, conversation: Conversation) {
        if (accountId != conversation.customerId && accountId != conversation.providerId) throw ConversationAccessDeniedException()
    }
    private fun otherParticipant(accountId: UUID, conversation: Conversation) = if (accountId == conversation.customerId) conversation.providerId else conversation.customerId
}

@Service
class NotificationService(
    private val notifications: NotificationStore,
    private val preferences: AccountPreferencesStore,
    private val clock: Clock,
) : PlatformNotificationPort {
    @Transactional
    override fun publish(notification: PlatformNotification) {
        val preference = preferences.findByAccountId(notification.recipientAccountId)
        val enabled = when {
            notification.type.startsWith("MESSAGE") -> preference?.communicationNotificationsEnabled ?: true
            notification.type.startsWith("SUPPORT") || notification.type.startsWith("REPORT") -> preference?.supportNotificationsEnabled ?: true
            else -> preference?.marketplaceNotificationsEnabled ?: true
        }
        if (!enabled) return
        notifications.save(Notification(UUID.randomUUID(), notification.recipientAccountId, notification.type,
            notification.title.take(160), notification.message.take(500), notification.relatedEntityType,
            notification.relatedEntityId, clock.instant(), null))
    }

    @Transactional(readOnly = true)
    fun list(accountId: UUID, unreadOnly: Boolean, page: Int, size: Int) =
        notifications.findByRecipient(accountId, unreadOnly, validPage(page), validSize(size)).toPage(page, size)

    @Transactional
    fun markRead(accountId: UUID, notificationId: UUID): Notification {
        val notification = notifications.findByIdForUpdate(notificationId) ?: throw NotificationNotFoundException()
        if (notification.recipientAccountId != accountId) throw NotificationAccessDeniedException()
        if (notification.readAt != null) return notification
        return notifications.save(notification.copy(readAt = clock.instant()))
    }

    @Transactional
    fun markAllRead(accountId: UUID) = notifications.markAllRead(accountId, clock.instant())
}

@Service
class BlockingService(
    private val blocks: UserBlockStore,
    private val relationships: MarketplaceRelationshipQuery,
    private val audit: PlatformAuditStore,
    private val clock: Clock,
) : MarketplaceInteractionPolicy {
    @Transactional(readOnly = true)
    override fun requireAllowed(firstAccountId: UUID, secondAccountId: UUID) {
        if (blocks.existsEitherDirection(firstAccountId, secondAccountId)) throw InteractionBlockedException()
    }

    @Transactional
    fun create(accountId: UUID, command: CreateBlockCommand): UserBlock {
        if (accountId == command.blockedAccountId || !relationships.hasRelationship(accountId, command.blockedAccountId)) throw BlockRelationshipRequiredException()
        if (blocks.exists(accountId, command.blockedAccountId)) throw DuplicateBlockException()
        val block = blocks.save(UserBlock(accountId, command.blockedAccountId, command.reason, clock.instant()))
        audit.save(PlatformAuditEvent(UUID.randomUUID(), accountId, "USER_BLOCK_CREATED", "ACCOUNT", command.blockedAccountId, block.createdAt))
        return block
    }

    @Transactional(readOnly = true)
    fun list(accountId: UUID) = blocks.findByBlocker(accountId)

    @Transactional
    fun remove(accountId: UUID, blockedId: UUID) {
        if (!blocks.delete(accountId, blockedId)) throw BlockNotFoundException()
        audit.save(PlatformAuditEvent(UUID.randomUUID(), accountId, "USER_BLOCK_REMOVED", "ACCOUNT", blockedId, clock.instant()))
    }
}

@Service
class ReportService(
    private val reports: UserReportStore,
    private val relationships: MarketplaceRelationshipQuery,
    private val notifications: PlatformNotificationPort,
    private val audit: PlatformAuditStore,
    private val clock: Clock,
) {
    @Transactional
    fun create(accountId: UUID, command: CreateReportCommand): UserReport {
        val description = command.description.trim()
        if (description.isEmpty() || description.length > 2000 || accountId == command.reportedAccountId) throw ReportContextInvalidException()
        if ((command.relatedEntityType == null) != (command.relatedEntityId == null)) throw ReportContextInvalidException()
        val valid = if (command.relatedEntityType == null) relationships.hasRelationship(accountId, command.reportedAccountId)
        else relationships.contextMatches(command.relatedEntityType, command.relatedEntityId!!, accountId, command.reportedAccountId)
        if (!valid) throw ReportContextInvalidException()
        val now = clock.instant()
        val report = reports.save(UserReport(UUID.randomUUID(), accountId, command.reportedAccountId,
            command.relatedEntityType, command.relatedEntityId, command.category, description, ReportStatus.OPEN,
            now, null, null, null))
        audit.save(PlatformAuditEvent(UUID.randomUUID(), accountId, "USER_REPORT_SUBMITTED", "REPORT", report.id, now))
        return report
    }

    @Transactional(readOnly = true)
    fun listMine(accountId: UUID, page: Int, size: Int) = reports.findByReporter(accountId, validPage(page), validSize(size)).toPage(page, size)

    @Transactional(readOnly = true)
    fun adminList(status: ReportStatus?, page: Int, size: Int) = reports.findAll(status, validPage(page), validSize(size)).toPage(page, size)

    @Transactional
    fun review(adminId: UUID, reportId: UUID, command: ReviewReportCommand): UserReport {
        if (command.status !in setOf(ReportStatus.IN_REVIEW, ReportStatus.RESOLVED, ReportStatus.DISMISSED)) throw ReportInvalidTransitionException()
        val report = reports.findByIdForUpdate(reportId) ?: throw ReportNotFoundException()
        if (report.status == command.status) return report
        if (report.status in setOf(ReportStatus.RESOLVED, ReportStatus.DISMISSED)) {
            throw ReportInvalidTransitionException()
        }
        if (report.status == ReportStatus.OPEN && command.status == ReportStatus.IN_REVIEW) {
            return reports.save(report.copy(status = ReportStatus.IN_REVIEW))
        }
        val note = command.moderationNote?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
        val now = clock.instant()
        val saved = reports.save(report.copy(status = command.status, reviewedAt = now, reviewedBy = adminId, moderationNote = note))
        audit.save(PlatformAuditEvent(UUID.randomUUID(), adminId, "USER_REPORT_REVIEWED", "REPORT", report.id, now))
        notifications.publish(PlatformNotification(report.reporterAccountId, "REPORT_REVIEWED", "Report reviewed",
            "Your marketplace report has been reviewed.", "REPORT", report.id))
        return saved
    }
}

@Service
class SupportCaseService(
    private val cases: SupportCaseStore,
    private val relationships: MarketplaceRelationshipQuery,
    private val bookingDisputes: BookingDisputePort,
    private val notifications: PlatformNotificationPort,
    private val audit: PlatformAuditStore,
    private val clock: Clock,
) {
    @Transactional
    fun create(accountId: UUID, command: CreateSupportCaseCommand): SupportCase {
        val description = command.description.trim()
        if (description.isEmpty() || description.length > 4000) throw SupportCaseInvalidException("Support case description must contain 1 to 4000 characters")
        if ((command.bookingId == null) == (command.requestId == null)) throw SupportCaseInvalidException("Provide exactly one Booking or request context")
        if (command.type == SupportCaseType.BOOKING_DISPUTE && command.bookingId == null) throw SupportCaseInvalidException("A Booking dispute requires a Booking")
        val relationship = command.bookingId?.let { relationships.forBooking(it, accountId) }
            ?: command.requestId?.let { relationships.forRequest(it, accountId) }
            ?: throw SupportCaseAccessDeniedException()
        if (command.type == SupportCaseType.BOOKING_DISPUTE && cases.findActiveDisputeByBooking(command.bookingId!!) != null) throw ActiveDisputeExistsException()
        val now = clock.instant()
        val supportCase = try {
            cases.save(SupportCase(UUID.randomUUID(), accountId, command.bookingId, command.requestId ?: relationship.requestId,
                command.type, description, SupportCaseStatus.OPEN, now, now, null, null, null, null))
        } catch (_: DataIntegrityViolationException) {
            throw ActiveDisputeExistsException()
        }
        if (command.type == SupportCaseType.BOOKING_DISPUTE) bookingDisputes.openDispute(accountId, command.bookingId!!, supportCase.id)
        val other = if (accountId == relationship.customerId) relationship.providerId else relationship.customerId
        notifications.publish(PlatformNotification(other, "SUPPORT_CASE_OPENED", "Support case opened",
            "A support case was opened for a marketplace interaction.", "SUPPORT_CASE", supportCase.id))
        val event = if (command.type == SupportCaseType.BOOKING_DISPUTE) "BOOKING_DISPUTE_OPENED" else "SUPPORT_CASE_OPENED"
        audit.save(PlatformAuditEvent(UUID.randomUUID(), accountId, event, "SUPPORT_CASE", supportCase.id, now))
        return supportCase
    }

    @Transactional(readOnly = true)
    fun listMine(accountId: UUID, page: Int, size: Int) = cases.findByCreator(accountId, validPage(page), validSize(size)).toPage(page, size)

    @Transactional(readOnly = true)
    fun getMine(accountId: UUID, caseId: UUID): SupportCase {
        val case = cases.findById(caseId) ?: throw SupportCaseNotFoundException()
        val relationship = case.bookingId?.let { relationships.forBooking(it, accountId) }
            ?: case.requestId?.let { relationships.forRequest(it, accountId) }
        if (relationship == null) throw SupportCaseAccessDeniedException()
        return case.copy(internalNote = null)
    }

    @Transactional(readOnly = true)
    fun adminList(status: SupportCaseStatus?, page: Int, size: Int) = cases.findAll(status, validPage(page), validSize(size)).toPage(page, size)

    @Transactional(readOnly = true)
    fun adminGet(caseId: UUID) = cases.findById(caseId) ?: throw SupportCaseNotFoundException()

    @Transactional
    fun review(adminId: UUID, caseId: UUID, command: ReviewSupportCaseCommand): SupportCase {
        val case = cases.findByIdForUpdate(caseId) ?: throw SupportCaseNotFoundException()
        if (!validSupportTransition(case.status, command.status)) {
            if (case.status == command.status) return case
            throw SupportCaseInvalidTransitionException()
        }
        val now = clock.instant()
        val terminal = command.status in setOf(SupportCaseStatus.RESOLVED, SupportCaseStatus.CLOSED)
        if (terminal && command.resolutionOutcome.isNullOrBlank()) throw SupportCaseInvalidException("A resolution outcome is required")
        val saved = cases.save(case.copy(status = command.status, updatedAt = now, assignedAdminId = adminId,
            internalNote = command.internalNote?.trim()?.takeIf { it.isNotEmpty() }?.take(2000),
            resolutionOutcome = if (terminal) command.resolutionOutcome!!.trim().take(1000) else case.resolutionOutcome,
            resolvedAt = if (terminal) (case.resolvedAt ?: now) else null))
        audit.save(PlatformAuditEvent(UUID.randomUUID(), adminId,
            if (terminal) "SUPPORT_CASE_RESOLVED" else "SUPPORT_CASE_REVIEWED", "SUPPORT_CASE", case.id, now))
        notifications.publish(PlatformNotification(case.creatorAccountId, "SUPPORT_CASE_UPDATED", "Support case updated",
            "Your support case status changed to ${command.status}.", "SUPPORT_CASE", case.id))
        return saved
    }

    private fun validSupportTransition(from: SupportCaseStatus, to: SupportCaseStatus) = when (from) {
        SupportCaseStatus.OPEN -> to in setOf(SupportCaseStatus.IN_REVIEW, SupportCaseStatus.RESOLVED)
        SupportCaseStatus.IN_REVIEW -> to == SupportCaseStatus.RESOLVED
        SupportCaseStatus.RESOLVED -> to == SupportCaseStatus.CLOSED
        SupportCaseStatus.CLOSED -> false
    }
}

@Service
class PreferencesService(private val store: AccountPreferencesStore, private val clock: Clock) {
    @Transactional(readOnly = true)
    fun get(accountId: UUID) = store.findByAccountId(accountId) ?: defaults(accountId)

    @Transactional
    fun update(accountId: UUID, command: UpdatePreferencesCommand) = store.save(AccountPreferences(accountId,
        command.preferredLanguage, command.marketplaceNotificationsEnabled, command.communicationNotificationsEnabled,
        command.supportNotificationsEnabled, clock.instant()))

    private fun defaults(accountId: UUID) = AccountPreferences(accountId, PreferredLanguage.EN, true, true, true, Instant.EPOCH)
}

@Service
class PlatformAuditService(private val store: PlatformAuditStore, private val clock: Clock) : PlatformAuditPort {
    @Transactional
    override fun record(command: PlatformAuditCommand) {
        store.save(PlatformAuditEvent(UUID.randomUUID(), command.actorAccountId, command.eventType,
            command.subjectType, command.subjectId, clock.instant()))
    }
}

private fun validPage(page: Int): Int {
    if (page < 0) throw CommunicationPaginationInvalidException()
    return page
}
private fun validSize(size: Int): Int {
    if (size !in 1..100) throw CommunicationPaginationInvalidException()
    return size
}
private fun <T> PageSlice<T>.toPage(page: Int, size: Int): PageResponse<T> {
    val pages = if (totalItems == 0L) 0 else ((totalItems + size - 1) / size).toInt()
    return PageResponse(items, page, size, totalItems, pages, page + 1 < pages)
}
