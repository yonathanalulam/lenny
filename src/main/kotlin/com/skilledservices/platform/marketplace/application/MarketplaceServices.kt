package com.skilledservices.platform.marketplace.application

import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.marketplace.domain.*
import com.skilledservices.platform.profiles.application.DiscoverableProviderProfile
import com.skilledservices.platform.profiles.application.ProviderDiscoveryProfileQuery
import com.skilledservices.platform.profiles.application.ProviderEligibilityService
import com.skilledservices.platform.profiles.domain.AvailabilityRange
import com.skilledservices.platform.shared.api.PageResponse
import com.skilledservices.platform.booking.application.ProviderBookingAvailabilityQuery
import com.skilledservices.platform.feedback.domain.ProviderRatingAggregate
import com.skilledservices.platform.feedback.domain.ProviderRatingQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class CategoryCommand(val parentId: UUID?, val nameEn: String, val nameAm: String, val description: String?, val active: Boolean)
data class CategoryUpdateCommand(val nameEn: String?, val nameAm: String?, val description: String?, val active: Boolean?)
data class CategoryTree(val category: ServiceCategory, val subcategories: List<ServiceCategory>)

@Service
class CategoryService(
    private val store: ServiceCategoryStore,
    private val identity: IdentityAccountQuery,
    private val clock: Clock,
) {
    @Transactional
    fun create(adminId: UUID, command: CategoryCommand): ServiceCategory {
        requireAdmin(adminId)
        command.parentId?.let { parentId ->
            val parent = store.findById(parentId) ?: throw CategoryNotFoundException()
            if (parent.parentId != null) throw CategoryInvalidException("Only one subcategory level is supported")
        }
        val en = clean(command.nameEn, "English name")
        val am = clean(command.nameAm, "Amharic name")
        if (store.existsByParentAndNameEn(command.parentId, en.lowercase())) throw CategoryDuplicateException()
        val now = clock.instant()
        return store.save(ServiceCategory(UUID.randomUUID(), command.parentId, en, am, cleanOptional(command.description, 1000), command.active, now, now))
    }

    @Transactional
    fun update(adminId: UUID, id: UUID, command: CategoryUpdateCommand): ServiceCategory {
        requireAdmin(adminId)
        val existing = store.findById(id) ?: throw CategoryNotFoundException()
        val en = command.nameEn?.let { clean(it, "English name") } ?: existing.nameEn
        val am = command.nameAm?.let { clean(it, "Amharic name") } ?: existing.nameAm
        if (store.existsByParentAndNameEn(existing.parentId, en.lowercase(), id)) throw CategoryDuplicateException()
        return store.save(existing.copy(nameEn = en, nameAm = am, description = command.description?.let { cleanOptional(it, 1000) } ?: existing.description, active = command.active ?: existing.active, updatedAt = clock.instant()))
    }

    @Transactional(readOnly = true)
    fun adminList(adminId: UUID) = identity.hasRole(adminId, AccountRole.ADMIN).also { if (!it) throw CategoryNotFoundException() }.let { store.findAll().sortedBy { c -> c.nameEn.lowercase() } }

    @Transactional(readOnly = true)
    fun publicTree(): List<CategoryTree> {
        val active = store.findAll().filter { it.active }
        return active.filter { it.parentId == null }.sortedBy { it.nameEn.lowercase() }
            .map { root -> CategoryTree(root, active.filter { it.parentId == root.id }.sortedBy { it.nameEn.lowercase() }) }
    }

    @Transactional(readOnly = true)
    fun publicGet(id: UUID): CategoryTree {
        val category = store.findById(id)?.takeIf { it.active } ?: throw CategoryNotFoundException()
        return CategoryTree(category, store.findAll().filter { it.active && it.parentId == id }.sortedBy { it.nameEn.lowercase() })
    }

    private fun requireAdmin(id: UUID) { if (!identity.hasRole(id, AccountRole.ADMIN)) throw CategoryNotFoundException() }
    private fun clean(value: String, label: String): String = value.trim().also { if (it.length !in 2..120) throw CategoryInvalidException("$label must contain 2 to 120 characters") }
    private fun cleanOptional(value: String?, max: Int) = value?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > max) throw CategoryInvalidException("Description is too long") }
}

@Service
class ProviderCategoryService(
    private val categories: ServiceCategoryStore,
    private val assignments: ProviderCategoryStore,
    private val profiles: ProviderDiscoveryProfileQuery,
    private val identity: IdentityAccountQuery,
    private val clock: Clock,
) {
    @Transactional
    fun replace(providerId: UUID, categoryIds: Set<UUID>): List<ServiceCategory> {
        if (!identity.hasRole(providerId, AccountRole.SERVICE_PROVIDER) || profiles.findByProviderId(providerId) == null) throw ProviderNotPublicException()
        if (categoryIds.isEmpty() || categoryIds.size > 20) throw CategoryInvalidException("Between 1 and 20 categories are required")
        val selected = categoryIds.map { categories.findById(it) ?: throw CategoryNotFoundException() }
        if (selected.any { !it.active }) throw CategoryInactiveException()
        val now = clock.instant()
        assignments.replace(providerId, selected.map { ProviderCategoryAssignment(providerId, it.id, now) })
        return selected.sortedBy { it.nameEn.lowercase() }
    }

    @Transactional(readOnly = true)
    fun get(providerId: UUID): List<ServiceCategory> {
        if (!identity.hasRole(providerId, AccountRole.SERVICE_PROVIDER)) throw ProviderNotPublicException()
        return assignments.findByProviderId(providerId).mapNotNull { categories.findById(it.categoryId) }.sortedBy { it.nameEn.lowercase() }
    }
}

data class ProviderDiscoveryFilter(
    val categoryId: UUID?, val serviceArea: String?, val available: Boolean?, val minExperience: Int?,
    val requestedDate: LocalDate?, val startTime: LocalTime?, val endTime: LocalTime?, val pricingText: String?,
    val minRating: Double?, val sort: String, val page: Int, val size: Int,
)

data class PublicProvider(
    val profile: DiscoverableProviderProfile,
    val categories: List<ServiceCategory>,
    val verificationApproved: Boolean = true,
    val averageRating: Double? = null,
    val ratingCount: Long = 0,
)

@Service
class ProviderDiscoveryService(
    private val profiles: ProviderDiscoveryProfileQuery,
    private val eligibility: ProviderEligibilityService,
    private val identity: IdentityAccountQuery,
    private val categoryStore: ServiceCategoryStore,
    private val assignmentStore: ProviderCategoryStore,
    private val bookingAvailability: ProviderBookingAvailabilityQuery,
    private val ratings: ProviderRatingQuery,
) {
    @Transactional(readOnly = true)
    fun search(filter: ProviderDiscoveryFilter): PageResponse<PublicProvider> {
        if (filter.page < 0 || filter.size !in 1..100) throw CategoryInvalidException("page must be non-negative and size must be between 1 and 100")
        if (filter.minExperience != null && filter.minExperience !in 0..80) throw CategoryInvalidException("minExperience must be between 0 and 80")
        if (filter.minRating != null && filter.minRating !in 1.0..5.0) throw CategoryInvalidException("minRating must be between 1 and 5")
        if ((filter.startTime == null) != (filter.endTime == null) || (filter.startTime != null && filter.startTime >= filter.endTime)) throw CategoryInvalidException("A valid availability time range is required")
        val allowedCategories = filter.categoryId?.let(::categoryAndChildren)
        val allProfiles = profiles.findAll()
        val ratingMap = ratings.aggregates(allProfiles.map { it.providerId }.toSet())
        var results = allProfiles.mapNotNull { publicProvider(it, ratingMap[it.providerId]) }
        results = results.filter { result ->
            (allowedCategories == null || result.categories.any { it.id in allowedCategories }) &&
                (filter.serviceArea.isNullOrBlank() || result.profile.serviceArea.contains(filter.serviceArea.trim(), true)) &&
                (filter.available == null || result.profile.available == filter.available) &&
                (filter.minExperience == null || result.profile.experienceYears >= filter.minExperience) &&
                (filter.minRating == null || (result.averageRating != null && result.averageRating >= filter.minRating)) &&
                (filter.pricingText.isNullOrBlank() || result.profile.pricingSummary.contains(filter.pricingText.trim(), true)) &&
                matchesAvailability(result.profile, filter)
        }
        results = when (filter.sort) {
            "updatedAt,desc" -> results.sortedWith(compareByDescending<PublicProvider> { it.profile.profileUpdatedAt }.thenBy { it.profile.providerId })
            "updatedAt,asc" -> results.sortedWith(compareBy<PublicProvider> { it.profile.profileUpdatedAt }.thenBy { it.profile.providerId })
            "experience,desc" -> results.sortedWith(compareByDescending<PublicProvider> { it.profile.experienceYears }.thenBy { it.profile.providerId })
            "experience,asc" -> results.sortedWith(compareBy<PublicProvider> { it.profile.experienceYears }.thenBy { it.profile.providerId })
            "rating,desc" -> results.sortedWith(compareByDescending<PublicProvider> { it.averageRating ?: -1.0 }.thenBy { it.profile.providerId })
            "rating,asc" -> results.sortedWith(compareBy<PublicProvider> { it.averageRating ?: Double.MAX_VALUE }.thenBy { it.profile.providerId })
            else -> throw UnsupportedProviderSortException()
        }
        val from = (filter.page * filter.size).coerceAtMost(results.size)
        val to = (from + filter.size).coerceAtMost(results.size)
        val totalPages = if (results.isEmpty()) 0 else (results.size + filter.size - 1) / filter.size
        return PageResponse(results.subList(from, to), filter.page, filter.size, results.size.toLong(), totalPages, filter.page + 1 < totalPages)
    }

    @Transactional(readOnly = true)
    fun get(providerId: UUID): PublicProvider = profiles.findByProviderId(providerId)
        ?.let { publicProvider(it, ratings.aggregates(setOf(providerId))[providerId]) } ?: throw ProviderNotPublicException()

    @Transactional(readOnly = true)
    fun compare(ids: List<UUID>): List<PublicProvider> {
        if (ids.distinct().size !in 2..5) throw CategoryInvalidException("Comparison requires 2 to 5 distinct provider IDs")
        return ids.distinct().map(::get)
    }

    private fun publicProvider(profile: DiscoverableProviderProfile, rating: ProviderRatingAggregate?): PublicProvider? {
        if (identity.accountStatus(profile.providerId) != AccountStatus.ACTIVE || !eligibility.isMarketplaceEligible(profile.providerId)) return null
        val categories = assignmentStore.findByProviderId(profile.providerId).mapNotNull { categoryStore.findById(it.categoryId) }.filter { it.active }
        return categories.takeIf { it.isNotEmpty() }?.let { PublicProvider(profile, it.sortedBy { c -> c.nameEn.lowercase() },
            averageRating = rating?.averageRating, ratingCount = rating?.ratingCount ?: 0) }
    }

    private fun categoryAndChildren(categoryId: UUID): Set<UUID> {
        val category = categoryStore.findById(categoryId) ?: throw CategoryNotFoundException()
        if (!category.active) throw CategoryInactiveException()
        return categoryStore.findAll().filter { it.active && (it.id == categoryId || it.parentId == categoryId) }.map { it.id }.toSet()
    }

    private fun matchesAvailability(profile: DiscoverableProviderProfile, filter: ProviderDiscoveryFilter): Boolean {
        if (filter.requestedDate == null) return true
        if (!profile.available) return false
        val day = filter.requestedDate.dayOfWeek
        val declared = profile.availabilityRanges.any { it.dayOfWeek == day && contains(it, filter.startTime, filter.endTime) }
        if (!declared || filter.startTime == null || filter.endTime == null) return declared
        val start = filter.requestedDate.atTime(filter.startTime).atZone(ADDIS_ABABA).toInstant()
        val end = filter.requestedDate.atTime(filter.endTime).atZone(ADDIS_ABABA).toInstant()
        return !bookingAvailability.hasBlockingConflict(profile.providerId, start, end)
    }

    private fun contains(range: AvailabilityRange, start: LocalTime?, end: LocalTime?) = start == null || (range.startTime <= start && range.endTime >= end)

    companion object { private val ADDIS_ABABA = ZoneId.of("Africa/Addis_Ababa") }
}
