package com.skilledservices.platform.marketplace.api

import com.skilledservices.platform.marketplace.application.CategorySuggestionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CategorySuggestionRequest(@field:NotBlank @field:Size(min = 10, max = 4000) val description: String)
data class CategorySuggestionResponse(val suggestedCategoryId: UUID, val name: LocalizedCategoryName,
    val confidence: Double?, val explanation: String?, val advisory: Boolean = true)

@RestController
@RequestMapping("/api/v1/customers/me/category-suggestions")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
class CategorySuggestionController(private val service: CategorySuggestionService) {
    @PostMapping
    @Operation(summary = "Get an advisory active-category suggestion; customer choice remains authoritative")
    fun suggest(@Valid @RequestBody request: CategorySuggestionRequest): CategorySuggestionResponse {
        val result = service.suggest(request.description)
        return CategorySuggestionResponse(result.category.id,
            LocalizedCategoryName(result.category.nameEn, result.category.nameAm), result.confidence, result.explanation)
    }
}
