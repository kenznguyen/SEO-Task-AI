package com.kenznguyen.seotaskai.domain.analyzer

import com.kenznguyen.seotaskai.domain.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAnalyzerTest {
    private val analyzer = TaskAnalyzer()

    @Test
    fun createsSeoPlanWithResearchSteps() {
        val plan = analyzer.analyze("Find keywords and run a website SEO audit")

        assertEquals(TaskType.SEO_RESEARCH, plan.type)
        assertTrue(plan.steps.any { it.contains("keyword", ignoreCase = true) })
        assertFalse(plan.requiresAccountAction)
    }

    @Test
    fun marksExternalEngagementAsHumanReviewed() {
        val plan = analyzer.analyze("Review engagement requirements and prepare a comment")

        assertEquals(TaskType.ENGAGEMENT_REVIEW, plan.type)
        assertTrue(plan.requiresAccountAction)
        assertTrue(plan.steps.any { it.contains("review", ignoreCase = true) })
    }

    @Test
    fun handlesBlankInstructions() {
        val plan = analyzer.analyze("   ")

        assertEquals(TaskType.UNKNOWN, plan.type)
        assertTrue(plan.steps.isEmpty())
    }
}
