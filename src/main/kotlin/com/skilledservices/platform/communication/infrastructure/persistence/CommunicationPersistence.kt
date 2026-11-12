package com.skilledservices.platform.communication.infrastructure.persistence

import com.skilledservices.platform.communication.domain.*
import com.skilledservices.platform.shared.pagination.PageSlice
import jakarta.persistence.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "conversations")
class ConversationEntity(
    @Id var id: UUID,
    @Column(name = "request_id") var requestId: UUID?,
    @Column(name = "booking_id") var bookingId: UUID?,
    @Column(name = "customer_id", nullable = false) var customerId: UUID,
    @Column(name = "provider_id", nullable = false) var providerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var state: ConversationState,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "last_activity_at", nullable = false) var lastActivityAt: Instant,
)

@Entity
@Table(name = "messages")
class MessageEntity(
    @Id var id: UUID,
    @Column(name = "conversation_id", nullable = false) var conversationId: UUID,
    @Column(name = "sender_account_id", nullable = false) var senderAccountId: UUID,
    @Column(nullable = false, length = 4000) var body: String,
    @Column(name = "sent_at", nullable = false) var sentAt: Instant,
    @Column(name = "read_at") var readAt: Instant?,
)

@Entity
@Table(name = "notifications")
class NotificationEntity(
    @Id var id: UUID,
    @Column(name = "recipient_account_id", nullable = false) var recipientAccountId: UUID,
    @Column(name = "notification_type", nullable = false, length = 80) var type: String,
    @Column(nullable = false, length = 160) var title: String,
    @Column(nullable = false, length = 500) var message: String,
    @Column(name = "related_entity_type", length = 50) var relatedEntityType: String?,
    @Column(name = "related_entity_id") var relatedEntityId: UUID?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "read_at") var readAt: Instant?,
)

data class UserBlockKey(var blockerAccountId: UUID? = null, var blockedAccountId: UUID? = null) : Serializable

@Entity
@Table(name = "user_blocks")
@IdClass(UserBlockKey::class)
class UserBlockEntity(
    @Id @Column(name = "blocker_account_id") var blockerAccountId: UUID,
    @Id @Column(name = "blocked_account_id") var blockedAccountId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "reason_category", length = 32) var reasonCategory: BlockReason?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

@Entity
@Table(name = "user_reports")
class UserReportEntity(
    @Id var id: UUID,
    @Column(name = "reporter_account_id", nullable = false) var reporterAccountId: UUID,
    @Column(name = "reported_account_id", nullable = false) var reportedAccountId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "related_entity_type", length = 32) var relatedEntityType: RelatedEntityType?,
    @Column(name = "related_entity_id") var relatedEntityId: UUID?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var category: ReportCategory,
    @Column(nullable = false, length = 2000) var description: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: ReportStatus,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "reviewed_at") var reviewedAt: Instant?,
    @Column(name = "reviewed_by") var reviewedBy: UUID?,
    @Column(name = "moderation_note", length = 1000) var moderationNote: String?,
)

@Entity
@Table(name = "support_cases")
class SupportCaseEntity(
    @Id var id: UUID,
    @Column(name = "creator_account_id", nullable = false) var creatorAccountId: UUID,
    @Column(name = "booking_id") var bookingId: UUID?,
    @Column(name = "request_id") var requestId: UUID?,
    @Enumerated(EnumType.STRING) @Column(name = "case_type", nullable = false, length = 32) var type: SupportCaseType,
    @Column(nullable = false, length = 4000) var description: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: SupportCaseStatus,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "assigned_admin_id") var assignedAdminId: UUID?,
    @Column(name = "internal_note", length = 2000) var internalNote: String?,
    @Column(name = "resolution_outcome", length = 1000) var resolutionOutcome: String?,
    @Column(name = "resolved_at") var resolvedAt: Instant?,
)

@Entity
@Table(name = "account_preferences")
class AccountPreferencesEntity(
    @Id @Column(name = "account_id") var accountId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "preferred_language", nullable = false, length = 2) var preferredLanguage: PreferredLanguage,
    @Column(name = "marketplace_notifications_enabled", nullable = false) var marketplaceNotificationsEnabled: Boolean,
    @Column(name = "communication_notifications_enabled", nullable = false) var communicationNotificationsEnabled: Boolean,
    @Column(name = "support_notifications_enabled", nullable = false) var supportNotificationsEnabled: Boolean,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "platform_audit_events")
class PlatformAuditEventEntity(
    @Id var id: UUID,
    @Column(name = "actor_account_id") var actorAccountId: UUID?,
    @Column(name = "event_type", nullable = false, length = 100) var eventType: String,
    @Column(name = "subject_type", nullable = false, length = 50) var subjectType: String,
    @Column(name = "subject_id", nullable = false) var subjectId: UUID,
    @Column(name = "occurred_at", nullable = false) var occurredAt: Instant,
)

interface ConversationJpaRepository : JpaRepository<ConversationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ConversationEntity?
    fun findByBookingId(bookingId: UUID): ConversationEntity?
    fun findByRequestIdAndCustomerIdAndProviderId(requestId: UUID, customerId: UUID, providerId: UUID): ConversationEntity?
    @Query("select c from ConversationEntity c where c.customerId = :accountId or c.providerId = :accountId order by c.lastActivityAt desc, c.id desc")
    fun findForParticipant(@Param("accountId") accountId: UUID, pageable: Pageable): org.springframework.data.domain.Page<ConversationEntity>
}

interface MessageJpaRepository : JpaRepository<MessageEntity, UUID> {
    fun findAllByConversationIdOrderBySentAtDescIdDesc(conversationId: UUID, pageable: Pageable): org.springframework.data.domain.Page<MessageEntity>
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MessageEntity m set m.readAt = :readAt where m.conversationId = :conversationId and m.senderAccountId <> :recipientId and m.readAt is null")
    fun markReceivedRead(@Param("conversationId") conversationId: UUID, @Param("recipientId") recipientId: UUID, @Param("readAt") readAt: Instant): Int
}

interface NotificationJpaRepository : JpaRepository<NotificationEntity, UUID> {
    fun findAllByRecipientAccountIdOrderByCreatedAtDescIdDesc(recipientId: UUID, pageable: Pageable): org.springframework.data.domain.Page<NotificationEntity>
    fun findAllByRecipientAccountIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(recipientId: UUID, pageable: Pageable): org.springframework.data.domain.Page<NotificationEntity>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationEntity n where n.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): NotificationEntity?
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationEntity n set n.readAt = :readAt where n.recipientAccountId = :recipientId and n.readAt is null")
    fun markAllRead(@Param("recipientId") recipientId: UUID, @Param("readAt") readAt: Instant): Int
}

interface UserBlockJpaRepository : JpaRepository<UserBlockEntity, UserBlockKey> {
    fun existsByBlockerAccountIdAndBlockedAccountId(blockerId: UUID, blockedId: UUID): Boolean
    @Query("select (count(b) > 0) from UserBlockEntity b where (b.blockerAccountId = :firstId and b.blockedAccountId = :secondId) or (b.blockerAccountId = :secondId and b.blockedAccountId = :firstId)")
    fun existsEitherDirection(@Param("firstId") firstId: UUID, @Param("secondId") secondId: UUID): Boolean
    fun findAllByBlockerAccountIdOrderByCreatedAtDesc(blockerId: UUID): List<UserBlockEntity>
}

interface UserReportJpaRepository : JpaRepository<UserReportEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from UserReportEntity r where r.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): UserReportEntity?
    fun findAllByReporterAccountIdOrderByCreatedAtDescIdDesc(reporterId: UUID, pageable: Pageable): org.springframework.data.domain.Page<UserReportEntity>
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): org.springframework.data.domain.Page<UserReportEntity>
    fun findAllByStatusOrderByCreatedAtDescIdDesc(status: ReportStatus, pageable: Pageable): org.springframework.data.domain.Page<UserReportEntity>
}

interface SupportCaseJpaRepository : JpaRepository<SupportCaseEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupportCaseEntity s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): SupportCaseEntity?
    @Query("select s from SupportCaseEntity s where s.bookingId = :bookingId and s.type = com.skilledservices.platform.communication.domain.SupportCaseType.BOOKING_DISPUTE and s.status in (com.skilledservices.platform.communication.domain.SupportCaseStatus.OPEN, com.skilledservices.platform.communication.domain.SupportCaseStatus.IN_REVIEW)")
    fun findActiveDisputeByBooking(@Param("bookingId") bookingId: UUID): SupportCaseEntity?
    fun findAllByCreatorAccountIdOrderByCreatedAtDescIdDesc(creatorId: UUID, pageable: Pageable): org.springframework.data.domain.Page<SupportCaseEntity>
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): org.springframework.data.domain.Page<SupportCaseEntity>
    fun findAllByStatusOrderByCreatedAtDescIdDesc(status: SupportCaseStatus, pageable: Pageable): org.springframework.data.domain.Page<SupportCaseEntity>
}

interface AccountPreferencesJpaRepository : JpaRepository<AccountPreferencesEntity, UUID>
interface PlatformAuditJpaRepository : JpaRepository<PlatformAuditEventEntity, UUID>

@Repository
class JpaConversationStore(private val repository: ConversationJpaRepository) : ConversationStore {
    override fun save(conversation: Conversation) = repository.saveAndFlush(conversation.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByBookingId(bookingId: UUID) = repository.findByBookingId(bookingId)?.toDomain()
    override fun findByRequestAndParticipants(requestId: UUID, customerId: UUID, providerId: UUID) = repository.findByRequestIdAndCustomerIdAndProviderId(requestId, customerId, providerId)?.toDomain()
    override fun findByParticipant(accountId: UUID, page: Int, size: Int) = repository.findForParticipant(accountId, PageRequest.of(page, size)).toSlice { it.toDomain() }
}

@Repository
class JpaMessageStore(private val repository: MessageJpaRepository) : MessageStore {
    override fun save(message: Message) = repository.saveAndFlush(message.toEntity()).toDomain()
    override fun findByConversation(conversationId: UUID, page: Int, size: Int) = repository.findAllByConversationIdOrderBySentAtDescIdDesc(conversationId, PageRequest.of(page, size)).toSlice { it.toDomain() }
    override fun markReceivedRead(conversationId: UUID, recipientId: UUID, readAt: Instant) = repository.markReceivedRead(conversationId, recipientId, readAt)
}

@Repository
class JpaNotificationStore(private val repository: NotificationJpaRepository) : NotificationStore {
    override fun save(notification: Notification) = repository.save(notification.toEntity()).toDomain()
    override fun findByRecipient(recipientId: UUID, unreadOnly: Boolean, page: Int, size: Int): PageSlice<Notification> {
        val result = if (unreadOnly) repository.findAllByRecipientAccountIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(recipientId, PageRequest.of(page, size))
        else repository.findAllByRecipientAccountIdOrderByCreatedAtDescIdDesc(recipientId, PageRequest.of(page, size))
        return result.toSlice { it.toDomain() }
    }
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun markAllRead(recipientId: UUID, readAt: Instant) = repository.markAllRead(recipientId, readAt)
}

@Repository
class JpaUserBlockStore(private val repository: UserBlockJpaRepository) : UserBlockStore {
    override fun save(block: UserBlock) = repository.save(block.toEntity()).toDomain()
    override fun exists(blockerId: UUID, blockedId: UUID) = repository.existsByBlockerAccountIdAndBlockedAccountId(blockerId, blockedId)
    override fun existsEitherDirection(firstId: UUID, secondId: UUID) = repository.existsEitherDirection(firstId, secondId)
    override fun delete(blockerId: UUID, blockedId: UUID): Boolean {
        val key = UserBlockKey(blockerId, blockedId)
        if (!repository.existsById(key)) return false
        repository.deleteById(key)
        return true
    }
    override fun findByBlocker(blockerId: UUID) = repository.findAllByBlockerAccountIdOrderByCreatedAtDesc(blockerId).map { it.toDomain() }
}

@Repository
class JpaUserReportStore(private val repository: UserReportJpaRepository) : UserReportStore {
    override fun save(report: UserReport) = repository.saveAndFlush(report.toEntity()).toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByReporter(reporterId: UUID, page: Int, size: Int) = repository.findAllByReporterAccountIdOrderByCreatedAtDescIdDesc(reporterId, PageRequest.of(page, size)).toSlice { it.toDomain() }
    override fun findAll(status: ReportStatus?, page: Int, size: Int) = (status?.let { repository.findAllByStatusOrderByCreatedAtDescIdDesc(it, PageRequest.of(page, size)) }
        ?: repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size))).toSlice { it.toDomain() }
}

@Repository
class JpaSupportCaseStore(private val repository: SupportCaseJpaRepository) : SupportCaseStore {
    override fun save(supportCase: SupportCase) = repository.saveAndFlush(supportCase.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findActiveDisputeByBooking(bookingId: UUID) = repository.findActiveDisputeByBooking(bookingId)?.toDomain()
    override fun findByCreator(creatorId: UUID, page: Int, size: Int) = repository.findAllByCreatorAccountIdOrderByCreatedAtDescIdDesc(creatorId, PageRequest.of(page, size)).toSlice { it.toDomain() }
    override fun findAll(status: SupportCaseStatus?, page: Int, size: Int) = (status?.let { repository.findAllByStatusOrderByCreatedAtDescIdDesc(it, PageRequest.of(page, size)) }
        ?: repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size))).toSlice { it.toDomain() }
}

@Repository
class JpaAccountPreferencesStore(private val repository: AccountPreferencesJpaRepository) : AccountPreferencesStore {
    override fun findByAccountId(accountId: UUID) = repository.findById(accountId).orElse(null)?.toDomain()
    override fun save(preferences: AccountPreferences) = repository.save(preferences.toEntity()).toDomain()
}

@Repository
class JpaPlatformAuditStore(private val repository: PlatformAuditJpaRepository) : PlatformAuditStore {
    override fun save(event: PlatformAuditEvent) { repository.save(event.toEntity()) }
}

private fun ConversationEntity.toDomain() = Conversation(id, requestId, bookingId, customerId, providerId, state, createdAt, lastActivityAt)
private fun Conversation.toEntity() = ConversationEntity(id, requestId, bookingId, customerId, providerId, state, createdAt, lastActivityAt)
private fun MessageEntity.toDomain() = Message(id, conversationId, senderAccountId, body, sentAt, readAt)
private fun Message.toEntity() = MessageEntity(id, conversationId, senderAccountId, body, sentAt, readAt)
private fun NotificationEntity.toDomain() = Notification(id, recipientAccountId, type, title, message, relatedEntityType, relatedEntityId, createdAt, readAt)
private fun Notification.toEntity() = NotificationEntity(id, recipientAccountId, type, title, message, relatedEntityType, relatedEntityId, createdAt, readAt)
private fun UserBlockEntity.toDomain() = UserBlock(blockerAccountId, blockedAccountId, reasonCategory, createdAt)
private fun UserBlock.toEntity() = UserBlockEntity(blockerAccountId, blockedAccountId, reasonCategory, createdAt)
private fun UserReportEntity.toDomain() = UserReport(id, reporterAccountId, reportedAccountId, relatedEntityType, relatedEntityId, category, description, status, createdAt, reviewedAt, reviewedBy, moderationNote)
private fun UserReport.toEntity() = UserReportEntity(id, reporterAccountId, reportedAccountId, relatedEntityType, relatedEntityId, category, description, status, createdAt, reviewedAt, reviewedBy, moderationNote)
private fun SupportCaseEntity.toDomain() = SupportCase(id, creatorAccountId, bookingId, requestId, type, description, status, createdAt, updatedAt, assignedAdminId, internalNote, resolutionOutcome, resolvedAt)
private fun SupportCase.toEntity() = SupportCaseEntity(id, creatorAccountId, bookingId, requestId, type, description, status, createdAt, updatedAt, assignedAdminId, internalNote, resolutionOutcome, resolvedAt)
private fun AccountPreferencesEntity.toDomain() = AccountPreferences(accountId, preferredLanguage, marketplaceNotificationsEnabled, communicationNotificationsEnabled, supportNotificationsEnabled, updatedAt)
private fun AccountPreferences.toEntity() = AccountPreferencesEntity(accountId, preferredLanguage, marketplaceNotificationsEnabled, communicationNotificationsEnabled, supportNotificationsEnabled, updatedAt)
private fun PlatformAuditEvent.toEntity() = PlatformAuditEventEntity(id, actorAccountId, eventType, subjectType, subjectId, occurredAt)
private fun <T : Any, R> org.springframework.data.domain.Page<T>.toSlice(transform: (T) -> R) = PageSlice(content.map(transform), totalElements)
