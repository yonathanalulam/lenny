package com.skilledservices.platform.shared.storage

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "protected_objects")
class ProtectedObjectEntity(
    @Id var id: UUID,
    @Column(name = "storage_key", nullable = false, length = 512) var storageKey: String,
    @Column(name = "created_by", nullable = false) var createdBy: UUID,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

interface ProtectedObjectJpaRepository : JpaRepository<ProtectedObjectEntity, UUID>

@Repository
class JpaProtectedObjectStore(private val repository: ProtectedObjectJpaRepository) : ProtectedObjectStore {
    override fun save(protectedObject: ProtectedObject) = repository.save(protectedObject.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
}

private fun ProtectedObjectEntity.toDomain() = ProtectedObject(id, storageKey, createdBy, createdAt)
private fun ProtectedObject.toEntity() = ProtectedObjectEntity(id, storageKey, createdBy, createdAt)
