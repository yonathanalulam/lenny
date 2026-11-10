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
import java.util.UUID

@Entity
@Table(name = "posting_fee_transactions")
class PostingFeeTransactionEntity(
    @Id var id: UUID,
    @Column(name = "request_id", nullable = false) var requestId: UUID,
    @Column(name = "customer_id", nullable = false) var customerId: UUID,
    @Column(nullable = false, precision = 12, scale = 2) var amount: BigDecimal,
    @Column(nullable = false, length = 3) var currency: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: PostingFeeStatus,
    @Column(name = "provider_identifier", nullable = false, length = 64) var providerIdentifier: String,
    @Column(name = "provider_reference_id", length = 255) var providerReferenceId: String?,
    @Column(name = "provider_event_id", length = 255) var providerEventId: String?,
    @Column(name = "idempotency_key", nullable = false) var idempotencyKey: UUID,
    @Column(name = "failure_code", length = 100) var failureCode: String?,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "verified_at") var verifiedAt: Instant?,
)

@Entity
@Table(name = "proposals")
class ProposalEntity(
    @Id var id: UUID,
    @Column(name = "request_id", nullable = false) var requestId: UUID,
    @Column(name = "provider_id", nullable = false) var providerId: UUID,
    @Column(name = "proposed_price", nullable = false, precision = 12, scale = 2) var proposedPrice: BigDecimal,
    @Column(name = "expected_timeline", nullable = false, length = 500) var expectedTimeline: String,
    @Column(name = "provider_message", nullable = false, length = 4000) var providerMessage: String,
    @Column(length = 2000) var conditions: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) var status: ProposalStatus,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "withdrawn_at") var withdrawnAt: Instant?,
    @Column(name = "rejected_at") var rejectedAt: Instant?,
)

@Entity
@Table(name = "marketplace_activity_events")
class MarketplaceActivityEntity(
    @Id var id: UUID,
    @Column(name = "aggregate_type", nullable = false, length = 50) var aggregateType: String,
    @Column(name = "aggregate_id", nullable = false) var aggregateId: UUID,
    @Column(name = "event_type", nullable = false, length = 80) var eventType: String,
    @Column(name = "actor_id") var actorId: UUID?,
    @Column(name = "occurred_at", nullable = false) var occurredAt: Instant,
)

interface PostingFeeTransactionJpaRepository : JpaRepository<PostingFeeTransactionEntity, UUID> {
    fun findAllByRequestIdOrderByCreatedAtDesc(requestId: UUID): List<PostingFeeTransactionEntity>
    fun findFirstByRequestIdAndStatus(requestId: UUID, status: PostingFeeStatus): PostingFeeTransactionEntity?
    fun findByProviderIdentifierAndProviderEventId(providerIdentifier: String, providerEventId: String): PostingFeeTransactionEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PostingFeeTransactionEntity p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): PostingFeeTransactionEntity?
}

interface ProposalJpaRepository : JpaRepository<ProposalEntity, UUID> {
    fun findByRequestIdAndProviderId(requestId: UUID, providerId: UUID): ProposalEntity?
    fun findAllByRequestIdOrderByCreatedAtDesc(requestId: UUID): List<ProposalEntity>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProposalEntity p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ProposalEntity?
}

interface MarketplaceActivityJpaRepository : JpaRepository<MarketplaceActivityEntity, UUID>

@Repository
class JpaPostingFeeTransactionStore(private val repository: PostingFeeTransactionJpaRepository) : PostingFeeTransactionStore {
    override fun save(transaction: PostingFeeTransaction) = repository.saveAndFlush(transaction.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findAllByRequestIdOrderByCreatedAtDesc(requestId).map { it.toDomain() }
    override fun findSuccessfulByRequestId(requestId: UUID) = repository.findFirstByRequestIdAndStatus(requestId, PostingFeeStatus.SUCCESSFUL)?.toDomain()
    override fun findByProviderEvent(provider: String, eventId: String) = repository.findByProviderIdentifierAndProviderEventId(provider, eventId)?.toDomain()
}

@Repository
class JpaProposalStore(private val repository: ProposalJpaRepository) : ProposalStore {
    override fun save(proposal: Proposal) = repository.saveAndFlush(proposal.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findByIdForUpdate(id: UUID) = repository.findByIdForUpdate(id)?.toDomain()
    override fun findByRequestAndProvider(requestId: UUID, providerId: UUID) = repository.findByRequestIdAndProviderId(requestId, providerId)?.toDomain()
    override fun findByRequestId(requestId: UUID) = repository.findAllByRequestIdOrderByCreatedAtDesc(requestId).map { it.toDomain() }
}

@Repository
class JpaMarketplaceActivityStore(private val repository: MarketplaceActivityJpaRepository) : MarketplaceActivityStore {
    override fun save(event: MarketplaceActivityEvent) { repository.save(event.toEntity()) }
}

private fun PostingFeeTransactionEntity.toDomain() = PostingFeeTransaction(id, requestId, customerId, amount, currency, status, providerIdentifier, providerReferenceId, providerEventId, idempotencyKey, failureCode, createdAt, updatedAt, verifiedAt)
private fun PostingFeeTransaction.toEntity() = PostingFeeTransactionEntity(id, requestId, customerId, amount, currency, status, providerIdentifier, providerReferenceId, providerEventId, idempotencyKey, failureCode, createdAt, updatedAt, verifiedAt)
private fun ProposalEntity.toDomain() = Proposal(id, requestId, providerId, proposedPrice, expectedTimeline, providerMessage, conditions, status, createdAt, updatedAt, withdrawnAt, rejectedAt)
private fun Proposal.toEntity() = ProposalEntity(id, requestId, providerId, proposedPrice, expectedTimeline, providerMessage, conditions, status, createdAt, updatedAt, withdrawnAt, rejectedAt)
private fun MarketplaceActivityEvent.toEntity() = MarketplaceActivityEntity(id, aggregateType, aggregateId, eventType, actorId, occurredAt)
