package com.kenznguyen.seotaskai.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskClassifierTest {
    private val classifier = TaskClassifier()

    @Test fun classifiesSeoResearch() {
        assertEquals(TaskType.SEO_RESEARCH, classifier.classify("Run a keyword SEO audit"))
    }

    @Test fun classifiesContentPreparation() {
        assertEquals(TaskType.CONTENT_PREPARATION, classifier.classify("Write an article draft"))
    }

    @Test fun classifiesEngagementAsReviewOnly() {
        assertEquals(TaskType.ENGAGEMENT_REVIEW, classifier.classify("Review engagement requirements"))
    }

    @Test fun returnsUnknownForBlankOrUnrecognizedInput() {
        assertEquals(TaskType.UNKNOWN, classifier.classify("  "))
        assertEquals(TaskType.UNKNOWN, classifier.classify("Book a flight"))
    }
}
