package com.kenznguyen.seotaskai.domain

enum class TaskType {
    SEO_RESEARCH,
    CONTENT_PREPARATION,
    ENGAGEMENT_REVIEW,
    UNKNOWN
}

class TaskClassifier {
    fun classify(instruction: String): TaskType {
        val text = instruction.trim().lowercase()
        if (text.isEmpty()) return TaskType.UNKNOWN

        return when {
            listOf("seo", "keyword", "backlink", "website audit", "search engine").any(text::contains) ->
                TaskType.SEO_RESEARCH
            listOf("write", "draft", "article", "content", "description").any(text::contains) ->
                TaskType.CONTENT_PREPARATION
            listOf("review", "comment", "like", "engagement").any(text::contains) ->
                TaskType.ENGAGEMENT_REVIEW
            else -> TaskType.UNKNOWN
        }
    }
}
