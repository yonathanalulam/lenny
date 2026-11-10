package com.skilledservices.platform.marketplace.infrastructure.persistence

import com.skilledservices.platform.marketplace.domain.ProviderCategoryAssignment
import com.skilledservices.platform.marketplace.domain.ProviderCategoryStore
import com.skilledservices.platform.marketplace.domain.ServiceCategory
import com.skilledservices.platform.marketplace.domain.ServiceCategoryStore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "service_categories")
class ServiceCategoryEntity(
    @Id var id: UUID,
    @Column(name = "parent_id") var parentId: UUID?,
    @Column(name = "name_en", nullable = false, length = 120) var nameEn: String,
    @Column(name = "name_am", nullable = false, length = 120) var nameAm: String,
    @Column(length = 1000) var description: String?,
    @Column(nullable = false) var active: Boolean,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

data class ProviderCategoryKey(var providerId: UUID? = null, var categoryId: UUID? = null) : Serializable

@Entity
@Table(name = "provider_service_categories")
@IdClass(ProviderCategoryKey::class)
class ProviderCategoryEntity(
    @Id @Column(name = "provider_id") var providerId: UUID,
    @Id @Column(name = "category_id") var categoryId: UUID,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)

interface ServiceCategoryJpaRepository : JpaRepository<ServiceCategoryEntity, UUID> {
    @Query("select count(c) > 0 from ServiceCategoryEntity c where " +
        "((:parentId is null and c.parentId is null) or c.parentId = :parentId) and lower(c.nameEn) = :name and (:excludingId is null or c.id <> :excludingId)")
    fun duplicate(@Param("parentId") parentId: UUID?, @Param("name") name: String, @Param("excludingId") excludingId: UUID?): Boolean
}

interface ProviderCategoryJpaRepository : JpaRepository<ProviderCategoryEntity, ProviderCategoryKey> {
    fun findAllByProviderId(providerId: UUID): List<ProviderCategoryEntity>
    fun findAllByCategoryIdIn(categoryIds: Set<UUID>): List<ProviderCategoryEntity>
    fun deleteAllByProviderId(providerId: UUID)
}

@Repository
class JpaServiceCategoryStore(private val repository: ServiceCategoryJpaRepository) : ServiceCategoryStore {
    override fun save(category: ServiceCategory) = repository.save(category.toEntity()).toDomain()
    override fun findById(id: UUID) = repository.findById(id).orElse(null)?.toDomain()
    override fun findAll() = repository.findAll().map { it.toDomain() }
    override fun existsByParentAndNameEn(parentId: UUID?, normalizedNameEn: String, excludingId: UUID?) =
        repository.duplicate(parentId, normalizedNameEn, excludingId)
}

@Repository
class JpaProviderCategoryStore(private val repository: ProviderCategoryJpaRepository) : ProviderCategoryStore {
    override fun replace(providerId: UUID, assignments: List<ProviderCategoryAssignment>): List<ProviderCategoryAssignment> {
        repository.deleteAllByProviderId(providerId)
        repository.flush()
        return repository.saveAll(assignments.map { ProviderCategoryEntity(it.providerId, it.categoryId, it.createdAt) }).map { it.toDomain() }
    }
    override fun findByProviderId(providerId: UUID) = repository.findAllByProviderId(providerId).map { it.toDomain() }
    override fun findByCategoryIds(categoryIds: Set<UUID>) = if (categoryIds.isEmpty()) emptyList() else repository.findAllByCategoryIdIn(categoryIds).map { it.toDomain() }
}

private fun ServiceCategoryEntity.toDomain() = ServiceCategory(id, parentId, nameEn, nameAm, description, active, createdAt, updatedAt)
private fun ServiceCategory.toEntity() = ServiceCategoryEntity(id, parentId, nameEn, nameAm, description, active, createdAt, updatedAt)
private fun ProviderCategoryEntity.toDomain() = ProviderCategoryAssignment(providerId, categoryId, createdAt)
