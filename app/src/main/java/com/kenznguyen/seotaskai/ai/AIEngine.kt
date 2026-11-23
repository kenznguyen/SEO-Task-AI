package com.kenznguyen.seotaskai.ai

/** Model-agnostic local text generation contract. */
interface AIEngine : AutoCloseable {
    val isReady: Boolean
    suspend fun generate(prompt: String): String
    override fun close()
}

enum class AnalysisSource { AI, RULE_FALLBACK }

data class AIAnalysisResult(
    val type: com.kenznguyen.seotaskai.domain.TaskType,
    val steps: List<String>,
    val requiresAccountAction: Boolean,
    val source: AnalysisSource,
    val warning: String? = null
)
