package com.skilledservices.platform.marketplace.api

import com.skilledservices.platform.marketplace.application.CategoryCommand
import com.skilledservices.platform.marketplace.application.CategoryTree
import com.skilledservices.platform.marketplace.application.CategoryUpdateCommand
import com.skilledservices.platform.marketplace.application.PublicProvider
import com.skilledservices.platform.marketplace.domain.ServiceCategory
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class LocalizedCategoryName(val en: String, val am: String)

data class CategoryResponse(
    val id: UUID,
    val parentId: UUID?,
    val name: LocalizedCategoryName,
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val subcategories: List<CategoryResponse> = emptyList(),
)

data class CreateCategoryRequest(
    val parentId: UUID? = null,
    @field:NotBlank @field:Size(min = 2, max = 120) val nameEn: String,
    @field:NotBlank @field:Size(min = 2, max = 120) val nameAm: String,
    @field:Size(max = 1000) val description: String? = null,
    val active: Boolean = true,
)

data class UpdateCategoryRequest(
    @field:Size(min = 2, max = 120) val nameEn: String? = null,
    @field:Size(min = 2, max = 120) val nameAm: String? = null,
    @field:Size(max = 1000) val description: String? = null,
    val active: Boolean? = null,
)

data class ProviderCategoriesRequest(
    @field:Size(min = 1, max = 20) val categoryIds: Set<UUID>,
)

data class PublicAvailabilityRangeResponse(
    val dayOfWeek: DayOfWeek,
    @field:Schema(type = "string", format = "time") val startTime: LocalTime,
    @field:Schema(type = "string", format = "time") val endTime: LocalTime,
)

data class PublicProviderResponse(
    val providerId: UUID,
    val professionalDescription: String,
    val categories: List<CategoryResponse>,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val portfolioSummary: String?,
    val profilePhotoAvailable: Boolean,
    val available: Boolean,
    val availability: List<PublicAvailabilityRangeResponse>,
    val verificationApproved: Boolean,
    val profileUpdatedAt: Instant,
)

internal fun CreateCategoryRequest.toCommand() = CategoryCommand(parentId, nameEn, nameAm, description, active)
internal fun UpdateCategoryRequest.toCommand() = CategoryUpdateCommand(nameEn, nameAm, description, active)
internal fun ServiceCategory.toResponse(subcategories: List<CategoryResponse> = emptyList()) = CategoryResponse(
    id, parentId, LocalizedCategoryName(nameEn, nameAm), description, active, createdAt, updatedAt, subcategories,
)
internal fun CategoryTree.toResponse() = category.toResponse(subcategories.map { it.toResponse() })
internal fun PublicProvider.toResponse() = PublicProviderResponse(
    profile.providerId, profile.professionalDescription, categories.map { it.toResponse() }, profile.skills,
    profile.experienceYears, profile.serviceArea, profile.pricingSummary, profile.portfolioSummary,
    profile.profilePhotoAvailable, profile.available,
    profile.availabilityRanges.map { PublicAvailabilityRangeResponse(it.dayOfWeek, it.startTime, it.endTime) },
    verificationApproved, profile.profileUpdatedAt,
)
