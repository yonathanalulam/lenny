package com.skilledservices.platform.integration.ai

import com.skilledservices.platform.marketplace.application.*
import com.skilledservices.platform.marketplace.domain.AiSuggestionUnavailableException
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class SpringAiCategorySuggestionAdapter(
    private val chatModels: ObjectProvider<ChatModel>,
    private val objectMapper: ObjectMapper,
) : CategorySuggestionPort {
    override fun suggest(description: String, candidates: List<CategorySuggestionCandidate>): RawCategorySuggestion {
        val model = chatModels.ifAvailable ?: throw AiSuggestionUnavailableException()
        val catalog = candidates.joinToString("\n") { "${it.id}|${it.nameEn}|${it.nameAm}|parent=${it.parentId ?: "ROOT"}" }
        val prompt = """
            You are an advisory category classifier. Choose exactly one ID from the supplied active category catalog.
            Never invent or alter an ID. Return JSON only with categoryId, confidence (0 to 1), and a short explanation.
            Do not make any marketplace decision beyond this suggestion.

            ACTIVE CATEGORY CATALOG:
            $catalog

            CUSTOMER DESCRIPTION:
            $description
        """.trimIndent()
        val response = try { model.call(prompt) } catch (_: Exception) { throw AiSuggestionUnavailableException() }
            ?: throw AiSuggestionUnavailableException()
        return runCatching {
            val json = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val node = objectMapper.readTree(json)
            RawCategorySuggestion(node.get("categoryId")?.asString(), node.get("confidence")?.takeUnless { it.isNull }?.asDouble(),
                node.get("explanation")?.takeUnless { it.isNull }?.asString())
        }.getOrElse { RawCategorySuggestion(null, null, null) }
    }
}
