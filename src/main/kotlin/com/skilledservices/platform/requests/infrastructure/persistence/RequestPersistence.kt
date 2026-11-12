package com.skilledservices.platform.requests.infrastructure.persistence

import com.skilledservices.platform.requests.domain.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "service_requests")
class ServiceRequestEntity(
    @Id var id: UUID,
    @Column(name = "customer_id", nullable = false) var customerId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "request_type", nullable = false, length = 32) var type: ServiceRequestType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: ServiceRequestStatus,
    @Column(name = "category_id", nullable = false) var categoryId: UUID,
    @Column(name = "provider_id") var providerId: UUID?,
    @Column(nullable = false, length = 4000) var description: String,
    @Column(name = "general_area", nullable = false, length = 160) var generalArea: String,
    @Column(name = "exact_location", nullable = false, length = 1000) var exactLocation: String,
    @Column(name = "preferred_date", nullable = false) var preferredDate: LocalDate,
    @Column(name = "preferred_start_time") var preferredStartTime: LocalTime?,
    @Column(name = "preferred_end_time") var preferredEndTime: LocalTime?,
    @Enumerated(EnumType.STRING) @Column(length = 32) var urgency: RequestUrgency?,
    @Column(name = "budget_min", precision = 12, scale = 2) var budgetMin: BigDecimal?,
    @Column(name = "budget_max", precision = 12, scale = 2) var budgetMax: BigDecimal?,
    @Column(name = "special_instructions", length = 4000) var specialInstructions: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "cancelled_at") var cancelledAt: Instant?,
)

@Entity
@Table(name = "invitations")
class InvitationEntity(
    @Id var id: UUID,
    @Column(name = "request_id", nullable = false) var requestId: UUID,
    @Column(name = "provider_id", nullable = false) var providerId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: InvitationStatus,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "request_attachments")
class RequestAttachmentEntity(
    @Id var id: UUID,
    @Column(name = "request_id", nullable = false) var requestId: UUID,
    @Column(name = "original_filename", nullable = false, length = 255) var originalFilename: String,
    @Column(name = "content_type", nullable = false, length = 100) var contentType: String,
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long,
    @Column(name = "protected_object_id", nullable = false) var protectedObjectId: UUID,
    @Column(name = "uploaded_at", nullable = false) var uploadedAt: Instant,
)

interface ServiceRequestJpaRepository : JpaRepository<ServiceRequestEntity, UUID> {
    fun findAllByCustomerIdOrderByCreatedAtDesc(customerId: UUID): List<ServiceRequestEntity>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequestEntity r where r.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ServiceRequestEntity?
    fun findAllByTypeAndStatusOrderByCreatedAtDescIdDesc(type: ServiceRequestType, status: ServiceRequestStatus): List<ServiceRequestEntity>
}
interface InvitationJpaRepository : JpaRepository<InvitationEntity, UUID> {
    fun findByRequestId(requestId: UUID): InvitationEntity?
    fun findAllByProviderIdOrderByCreatedAtDesc(providerId: UUID): List<InvitationEntity>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InvitationEntity i where i.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): InvitationEntity?
}
interface RequestAttachmentJpaRepository : JpaRepository<RequestAttachmentEntity, UUID> {
    fun findAllByRequestIdOrderByUploadedAt(requestId: UUID): List<RequestAttachmentEntity>
    fun findAllByRequestIdInOrderByRequestIdAscUploadedAtAsc(requestIds: Set<UUID>): List<RequestAttachmentEntity>
}

@Repository class JpaServiceRequestStore(private val repository: ServiceRequestJpaRepository) : ServiceRequestStore {
    override fun save(request: ServiceRequest) = repository.save(request.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByCustomerId(customerId: UUID) = repository.findAllByCustomerIdOrderByCreatedAtDesc(customerId).map { it.toDomain() }
    override fun findOpenJobPostings() = repository.findAllByTypeAndStatusOrderByCreatedAtDescIdDesc(ServiceRequestType.JOB_POSTING, ServiceRequestStatus.OPEN).map { it.toDomain() }
}
@Repository class JpaInvitationStore(private val repository: InvitationJpaRepository) : InvitationStore {
    override fun save(invitation: DirectInvitation) = repository.save(invitation.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findByRequestId(requestId)?.toDomain()
    override fun findByProviderId(providerId: UUID) = repository.findAllByProviderIdOrderByCreatedAtDesc(providerId).map { it.toDomain() }
}
@Repository class JpaRequestAttachmentStore(private val repository: RequestAttachmentJpaRepository) : RequestAttachmentStore {
    override fun save(attachment: RequestAttachment) = repository.save(attachment.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findAllByRequestIdOrderByUploadedAt(requestId).map { it.toDomain() }
    override fun findByRequestIds(requestIds: Set<UUID>) = if (requestIds.isEmpty()) emptyMap() else
        repository.findAllByRequestIdInOrderByRequestIdAscUploadedAtAsc(requestIds).map { it.toDomain() }.groupBy { it.requestId }
}

private fun ServiceRequestEntity.toDomain() = ServiceRequest(id, customerId, type, status, categoryId, providerId, description, generalArea, exactLocation, preferredDate, preferredStartTime, preferredEndTime, urgency, budgetMin, budgetMax, specialInstructions, createdAt, updatedAt, cancelledAt)
private fun ServiceRequest.toEntity() = ServiceRequestEntity(id, customerId, type, status, categoryId, providerId, description, generalArea, exactLocation, preferredDate, preferredStartTime, preferredEndTime, urgency, budgetMin, budgetMax, specialInstructions, createdAt, updatedAt, cancelledAt)
private fun InvitationEntity.toDomain() = DirectInvitation(id, requestId, providerId, status, createdAt, updatedAt)
private fun DirectInvitation.toEntity() = InvitationEntity(id, requestId, providerId, status, createdAt, updatedAt)
private fun RequestAttachmentEntity.toDomain() = RequestAttachment(id, requestId, originalFilename, contentType, sizeBytes, protectedObjectId, uploadedAt)
private fun RequestAttachment.toEntity() = RequestAttachmentEntity(id, requestId, originalFilename, contentType, sizeBytes, protectedObjectId, uploadedAt)
