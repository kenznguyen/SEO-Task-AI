package com.kenznguyen.seotaskai.domain.analyzer

import com.kenznguyen.seotaskai.domain.TaskClassifier
import com.kenznguyen.seotaskai.domain.TaskType

data class TaskPlan(
    val type: TaskType,
    val steps: List<String>,
    val requiresAccountAction: Boolean
)

class TaskAnalyzer(private val classifier: TaskClassifier = TaskClassifier()) {
    fun analyze(instruction: String): TaskPlan {
        val text = instruction.trim()
        if (text.isEmpty()) return TaskPlan(TaskType.UNKNOWN, emptyList(), false)

        val type = classifier.classify(text)
        val steps = when (type) {
            TaskType.SEO_RESEARCH -> listOf(
                "Identify the target website and search intent",
                "Research relevant keywords and search competitors",
                "Review title, description, headings and canonical URL",
                "Prepare findings and recommendations for user review"
            )
            TaskType.CONTENT_PREPARATION -> listOf(
                "Clarify audience, topic and content requirements",
                "Prepare an outline and draft",
                "Check factual accuracy, originality and SEO structure",
                "Review the final content before publishing"
            )
            TaskType.ENGAGEMENT_REVIEW -> listOf(
                "Read the platform rules and task requirements",
                "Assess whether the requested engagement is genuine and permitted",
                "Prepare a recommendation for human review; do not automate artificial engagement"
            )
            TaskType.UNKNOWN -> listOf(
                "Review the instruction and identify the expected deliverable",
                "Clarify missing requirements before taking action"
            )
        }
        return TaskPlan(
            type = type,
            steps = steps,
            requiresAccountAction = type == TaskType.ENGAGEMENT_REVIEW
        )
    }
}
