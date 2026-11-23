package com.kenznguyen.seotaskai.ai

import com.kenznguyen.seotaskai.domain.TaskType
import com.kenznguyen.seotaskai.domain.analyzer.TaskPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskAnalyzerTest {
    @Test
    fun usesAiClassificationWhenEngineReturnsValidStructuredOutput() = runBlocking {
        val analyzer = LocalTaskAnalyzer(
            engine = FakeAIEngine("""TYPE=SEO_RESEARCH
STEPS=Inspect target URL|Research search intent
REQUIRES_ACCOUNT_ACTION=false"""),
            fallback = { TaskPlan(TaskType.UNKNOWN, listOf("fallback"), false) }
        )

        val result = analyzer.analyze("Please inspect this website")

        assertEquals(TaskType.SEO_RESEARCH, result.type)
        assertEquals(listOf("Inspect target URL", "Research search intent"), result.steps)
        assertEquals(AnalysisSource.AI, result.source)
    }

    @Test
    fun fallsBackWhenEngineIsUnavailable() = runBlocking {
        val analyzer = LocalTaskAnalyzer(
            engine = FakeAIEngine(failure = IllegalStateException("model not loaded")),
            fallback = { TaskPlan(TaskType.CONTENT_PREPARATION, listOf("Draft content"), false) }
        )

        val result = analyzer.analyze("write an article")

        assertEquals(TaskType.CONTENT_PREPARATION, result.type)
        assertEquals(AnalysisSource.RULE_FALLBACK, result.source)
        assertTrue(result.warning!!.contains("model not loaded"))
    }

    @Test
    fun rejectsUnknownAiTypeAndUsesFallback() = runBlocking {
        val analyzer = LocalTaskAnalyzer(
            engine = FakeAIEngine("TYPE=HACK_ACCOUNT"),
            fallback = { TaskPlan(TaskType.UNKNOWN, listOf("Clarify task"), false) }
        )

        assertEquals(AnalysisSource.RULE_FALLBACK, analyzer.analyze("task").source)
    }
}

private class FakeAIEngine(
    private val output: String? = null,
    private val failure: Throwable? = null
) : AIEngine {
    override val isReady: Boolean = true
    override suspend fun generate(prompt: String): String {
        failure?.let { throw it }
        return output.orEmpty()
    }
    override fun close() = Unit
}
