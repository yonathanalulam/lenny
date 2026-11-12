package com.skilledservices.platform.communication.domain

import com.skilledservices.platform.shared.pagination.PageSlice
import java.time.Instant
import java.util.UUID

enum class ConversationState { ACTIVE, CLOSED }
enum class BlockReason { HARASSMENT, SAFETY, SPAM, OTHER }
enum class ReportCategory { HARASSMENT, SAFETY, SPAM, FRAUD, INAPPROPRIATE_CONTENT, OTHER }
enum class ReportStatus { OPEN, IN_REVIEW, RESOLVED, DISMISSED }
enum class RelatedEntityType { USER, REQUEST, BOOKING, CONVERSATION }
enum class SupportCaseType { SUPPORT, BOOKING_DISPUTE }
enum class SupportCaseStatus { OPEN, IN_REVIEW, RESOLVED, CLOSED }
enum class PreferredLanguage { AM, EN }

data class Conversation(
    val id: UUID,
    val requestId: UUID?,
    val bookingId: UUID?,
    val customerId: UUID,
    val providerId: UUID,
    val state: ConversationState,
    val createdAt: Instant,
    val lastActivityAt: Instant,
)

data class Message(
    val id: UUID,
    val conversationId: UUID,
    val senderAccountId: UUID,
    val body: String,
    val sentAt: Instant,
    val readAt: Instant?,
)

data class Notification(
    val id: UUID,
    val recipientAccountId: UUID,
    val type: String,
    val title: String,
    val message: String,
    val relatedEntityType: String?,
    val relatedEntityId: UUID?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class UserBlock(
    val blockerAccountId: UUID,
    val blockedAccountId: UUID,
    val reasonCategory: BlockReason?,
    val createdAt: Instant,
)

data class UserReport(
    val id: UUID,
    val reporterAccountId: UUID,
    val reportedAccountId: UUID,
    val relatedEntityType: RelatedEntityType?,
    val relatedEntityId: UUID?,
    val category: ReportCategory,
    val description: String,
    val status: ReportStatus,
    val createdAt: Instant,
    val reviewedAt: Instant?,
    val reviewedBy: UUID?,
    val moderationNote: String?,
)

data class SupportCase(
    val id: UUID,
    val creatorAccountId: UUID,
    val bookingId: UUID?,
    val requestId: UUID?,
    val type: SupportCaseType,
    val description: String,
    val status: SupportCaseStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val assignedAdminId: UUID?,
    val internalNote: String?,
    val resolutionOutcome: String?,
    val resolvedAt: Instant?,
)

data class AccountPreferences(
    val accountId: UUID,
    val preferredLanguage: PreferredLanguage,
    val marketplaceNotificationsEnabled: Boolean,
    val communicationNotificationsEnabled: Boolean,
    val supportNotificationsEnabled: Boolean,
    val updatedAt: Instant,
)

data class PlatformAuditEvent(
    val id: UUID,
    val actorAccountId: UUID?,
    val eventType: String,
    val subjectType: String,
    val subjectId: UUID,
    val occurredAt: Instant,
)

interface ConversationStore {
    fun save(conversation: Conversation): Conversation
    fun findById(id: UUID): Conversation?
    fun findByIdForUpdate(id: UUID): Conversation?
    fun findByBookingId(bookingId: UUID): Conversation?
    fun findByRequestAndParticipants(requestId: UUID, customerId: UUID, providerId: UUID): Conversation?
    fun findByParticipant(accountId: UUID, page: Int, size: Int): PageSlice<Conversation>
}

interface MessageStore {
    fun save(message: Message): Message
    fun findByConversation(conversationId: UUID, page: Int, size: Int): PageSlice<Message>
    fun markReceivedRead(conversationId: UUID, recipientId: UUID, readAt: Instant): Int
}

interface NotificationStore {
    fun save(notification: Notification): Notification
    fun findByRecipient(recipientId: UUID, unreadOnly: Boolean, page: Int, size: Int): PageSlice<Notification>
    fun findByIdForUpdate(id: UUID): Notification?
    fun markAllRead(recipientId: UUID, readAt: Instant): Int
}

interface UserBlockStore {
    fun save(block: UserBlock): UserBlock
    fun exists(blockerId: UUID, blockedId: UUID): Boolean
    fun existsEitherDirection(firstId: UUID, secondId: UUID): Boolean
    fun delete(blockerId: UUID, blockedId: UUID): Boolean
    fun findByBlocker(blockerId: UUID): List<UserBlock>
}

interface UserReportStore {
    fun save(report: UserReport): UserReport
    fun findByIdForUpdate(id: UUID): UserReport?
    fun findByReporter(reporterId: UUID, page: Int, size: Int): PageSlice<UserReport>
    fun findAll(status: ReportStatus?, page: Int, size: Int): PageSlice<UserReport>
}

interface SupportCaseStore {
    fun save(supportCase: SupportCase): SupportCase
    fun findById(id: UUID): SupportCase?
    fun findByIdForUpdate(id: UUID): SupportCase?
    fun findActiveDisputeByBooking(bookingId: UUID): SupportCase?
    fun findByCreator(creatorId: UUID, page: Int, size: Int): PageSlice<SupportCase>
    fun findAll(status: SupportCaseStatus?, page: Int, size: Int): PageSlice<SupportCase>
}

interface AccountPreferencesStore {
    fun findByAccountId(accountId: UUID): AccountPreferences?
    fun save(preferences: AccountPreferences): AccountPreferences
}

interface PlatformAuditStore {
    fun save(event: PlatformAuditEvent)
}

open class CommunicationException(val code: String, message: String) : RuntimeException(message)
class ConversationNotFoundException : CommunicationException("CONVERSATION_NOT_FOUND", "Conversation was not found")
class ConversationAccessDeniedException : CommunicationException("CONVERSATION_ACCESS_DENIED", "Conversation is not accessible to this account")
class ConversationRelationshipRequiredException : CommunicationException("CONVERSATION_RELATIONSHIP_REQUIRED", "A marketplace relationship is required")
class ConversationClosedException : CommunicationException("CONVERSATION_CLOSED", "Conversation is closed")
class MessageInvalidException : CommunicationException("MESSAGE_INVALID", "Message body must contain 1 to 4000 characters")
class InteractionBlockedException : CommunicationException("INTERACTION_BLOCKED", "This interaction is blocked")
class BlockRelationshipRequiredException : CommunicationException("BLOCK_RELATIONSHIP_REQUIRED", "A prior marketplace interaction is required")
class DuplicateBlockException : CommunicationException("BLOCK_ALREADY_EXISTS", "This account is already blocked")
class BlockNotFoundException : CommunicationException("BLOCK_NOT_FOUND", "Block was not found")
class ReportContextInvalidException : CommunicationException("REPORT_CONTEXT_INVALID", "Report context does not belong to these participants")
class ReportNotFoundException : CommunicationException("REPORT_NOT_FOUND", "Report was not found")
class ReportInvalidTransitionException : CommunicationException("REPORT_INVALID_TRANSITION", "Report cannot transition to the requested status")
class SupportCaseNotFoundException : CommunicationException("SUPPORT_CASE_NOT_FOUND", "Support case was not found")
class SupportCaseAccessDeniedException : CommunicationException("SUPPORT_CASE_ACCESS_DENIED", "Support case is not accessible to this account")
class SupportCaseInvalidException(message: String) : CommunicationException("SUPPORT_CASE_INVALID", message)
class SupportCaseInvalidTransitionException : CommunicationException("SUPPORT_CASE_INVALID_TRANSITION", "Support case cannot transition to the requested status")
class ActiveDisputeExistsException : CommunicationException("ACTIVE_DISPUTE_EXISTS", "An active dispute already exists for this Booking")
class NotificationNotFoundException : CommunicationException("NOTIFICATION_NOT_FOUND", "Notification was not found")
class NotificationAccessDeniedException : CommunicationException("NOTIFICATION_ACCESS_DENIED", "Notification belongs to another account")
class PreferenceInvalidException : CommunicationException("PREFERENCE_INVALID", "Account preferences are invalid")
class CommunicationPaginationInvalidException : CommunicationException("PAGINATION_INVALID", "page must be non-negative and size must be between 1 and 100")
