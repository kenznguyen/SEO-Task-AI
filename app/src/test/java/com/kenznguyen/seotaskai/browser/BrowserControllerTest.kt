package com.kenznguyen.seotaskai.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserControllerTest {
    private val controller = BrowserController()

    @Test
    fun addsHttpsWhenSchemeIsMissing() {
        assertEquals("https://example.com", controller.normalizeUrl("example.com"))
    }

    @Test
    fun upgradesInilahkoranHttpToHttps() {
        assertEquals(
            "https://software.inilahkoran.id/",
            controller.normalizeUrl("http://software.inilahkoran.id/")
        )
    }

    @Test
    fun preservesOtherExplicitHttpAndHttpsUrls() {
        assertEquals("http://example.com", controller.normalizeUrl("http://example.com"))
        assertEquals("https://example.com/path", controller.normalizeUrl("https://example.com/path"))
    }

    @Test
    fun rejectsEmptyAndUnsupportedSchemes() {
        assertThrows(IllegalArgumentException::class.java) { controller.normalizeUrl("  ") }
        assertThrows(IllegalArgumentException::class.java) { controller.normalizeUrl("javascript://alert(1)") }
    }
}
