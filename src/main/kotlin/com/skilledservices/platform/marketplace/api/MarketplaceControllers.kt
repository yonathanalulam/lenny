package com.skilledservices.platform.marketplace.api

import com.skilledservices.platform.marketplace.application.CategoryService
import com.skilledservices.platform.marketplace.application.ProviderCategoryService
import com.skilledservices.platform.marketplace.application.ProviderDiscoveryFilter
import com.skilledservices.platform.marketplace.application.ProviderDiscoveryService
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Category administration")
class AdminCategoryController(private val service: CategoryService) {
    @PostMapping
    @Operation(summary = "Create a root service category or one-level subcategory")
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateCategoryRequest) =
        service.create(jwt.accountId(), request.toCommand()).toResponse()

    @PatchMapping("/{categoryId}")
    @Operation(summary = "Update category names, description, or activation state")
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable categoryId: UUID, @Valid @RequestBody request: UpdateCategoryRequest) =
        service.update(jwt.accountId(), categoryId, request.toCommand()).toResponse()

    @GetMapping
    @Operation(summary = "List all categories, including inactive categories")
    fun list(@AuthenticationPrincipal jwt: Jwt) = service.adminList(jwt.accountId()).map { it.toResponse() }
}

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Public service categories")
class PublicCategoryController(private val service: CategoryService) {
    @GetMapping
    @Operation(summary = "Browse the active service-category hierarchy")
    fun list() = service.publicTree().map { it.toResponse() }

    @GetMapping("/{categoryId}")
    @Operation(summary = "Get one active category and its active subcategories")
    fun get(@PathVariable categoryId: UUID) = service.publicGet(categoryId).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/categories")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider service categories")
class ProviderCategoryController(private val service: ProviderCategoryService) {
    @GetMapping fun get(@AuthenticationPrincipal jwt: Jwt) = service.get(jwt.accountId()).map { it.toResponse() }
    @PutMapping fun replace(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: ProviderCategoriesRequest) =
        service.replace(jwt.accountId(), request.categoryIds).map { it.toResponse() }
}

@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Approved provider discovery")
class ProviderDiscoveryController(private val service: ProviderDiscoveryService) {
    @GetMapping
    @Operation(summary = "Search marketplace-eligible providers", description = "Availability is a discovery hint, not a reservation.")
    fun search(
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) serviceArea: String?,
        @RequestParam(required = false) available: Boolean?,
        @RequestParam(required = false) minExperience: Int?,
        @RequestParam(required = false) requestedDate: LocalDate?,
        @RequestParam(required = false) startTime: LocalTime?,
        @RequestParam(required = false) endTime: LocalTime?,
        @RequestParam(required = false) pricingText: String?,
        @RequestParam(required = false) minRating: Double?,
        @RequestParam(defaultValue = "updatedAt,desc") sort: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<PublicProviderResponse> {
        val result = service.search(ProviderDiscoveryFilter(categoryId, serviceArea, available, minExperience, requestedDate, startTime, endTime, pricingText, minRating, sort, page, size))
        return PageResponse(result.items.map { it.toResponse() }, result.page, result.size, result.totalItems, result.totalPages, result.hasNext)
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare 2 to 5 approved providers without ranking them")
    fun compare(@RequestParam ids: List<UUID>) = service.compare(ids).map { it.toResponse() }

    @GetMapping("/{providerId}")
    @Operation(summary = "Get the public projection of an approved provider")
    fun get(@PathVariable providerId: UUID) = service.get(providerId).toResponse()
}

internal fun Jwt.accountId(): UUID = UUID.fromString(subject)
