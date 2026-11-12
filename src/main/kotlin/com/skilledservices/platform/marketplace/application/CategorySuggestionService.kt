package com.skilledservices.platform.marketplace.application

import com.skilledservices.platform.marketplace.domain.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CategorySuggestionCandidate(val id: UUID, val nameEn: String, val nameAm: String, val parentId: UUID?)
data class RawCategorySuggestion(val categoryId: String?, val confidence: Double?, val explanation: String?)
data class CategorySuggestionResult(val category: ServiceCategory, val confidence: Double?, val explanation: String?)

fun interface CategorySuggestionPort {
    fun suggest(description: String, candidates: List<CategorySuggestionCandidate>): RawCategorySuggestion
}

@Service
class CategorySuggestionService(
    private val categories: ServiceCategoryStore,
    private val provider: CategorySuggestionPort,
) {
    @Transactional(readOnly = true)
    fun suggest(description: String): CategorySuggestionResult {
        val clean = description.trim()
        if (clean.length !in 10..4000) throw CategoryInvalidException("description must contain 10 to 4000 characters")
        val active = categories.findAll().filter { it.active }
        if (active.isEmpty()) throw AiSuggestionUnavailableException()
        val candidates = active.map { CategorySuggestionCandidate(it.id, it.nameEn, it.nameAm, it.parentId) }
        val raw = provider.suggest(clean, candidates)
        val id = runCatching { UUID.fromString(raw.categoryId) }.getOrElse { throw AiSuggestionInvalidException() }
        val category = active.firstOrNull { it.id == id } ?: throw AiSuggestionInvalidException()
        if (raw.confidence != null && raw.confidence !in 0.0..1.0) throw AiSuggestionInvalidException()
        val explanation = raw.explanation?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
        return CategorySuggestionResult(category, raw.confidence, explanation)
    }
}
