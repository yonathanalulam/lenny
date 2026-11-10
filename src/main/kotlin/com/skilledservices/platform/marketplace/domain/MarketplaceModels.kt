package com.skilledservices.platform.marketplace.domain

import java.time.Instant
import java.util.UUID

data class ServiceCategory(
    val id: UUID,
    val parentId: UUID?,
    val nameEn: String,
    val nameAm: String,
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ProviderCategoryAssignment(
    val providerId: UUID,
    val categoryId: UUID,
    val createdAt: Instant,
)

interface ServiceCategoryStore {
    fun save(category: ServiceCategory): ServiceCategory
    fun findById(id: UUID): ServiceCategory?
    fun findAll(): List<ServiceCategory>
    fun existsByParentAndNameEn(parentId: UUID?, normalizedNameEn: String, excludingId: UUID? = null): Boolean
}

interface ProviderCategoryStore {
    fun replace(providerId: UUID, assignments: List<ProviderCategoryAssignment>): List<ProviderCategoryAssignment>
    fun findByProviderId(providerId: UUID): List<ProviderCategoryAssignment>
    fun findByCategoryIds(categoryIds: Set<UUID>): List<ProviderCategoryAssignment>
}

open class MarketplaceException(val code: String, message: String) : RuntimeException(message)
class CategoryNotFoundException : MarketplaceException("CATEGORY_NOT_FOUND", "Service category was not found")
class CategoryInactiveException : MarketplaceException("CATEGORY_INACTIVE", "Service category is inactive")
class CategoryInvalidException(message: String) : MarketplaceException("CATEGORY_INVALID", message)
class CategoryDuplicateException : MarketplaceException("CATEGORY_DUPLICATE", "A category with this English name already exists under the same parent")
class ProviderNotPublicException : MarketplaceException("PROVIDER_NOT_PUBLICLY_AVAILABLE", "Provider was not found")
class UnsupportedProviderSortException : MarketplaceException("UNSUPPORTED_PROVIDER_SORT", "Unsupported provider sort")
