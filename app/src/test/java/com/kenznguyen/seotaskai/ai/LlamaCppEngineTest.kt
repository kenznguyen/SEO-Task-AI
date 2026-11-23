package com.kenznguyen.seotaskai.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlamaCppEngineTest {
    @Test
    fun refusesToLoadWhenModelFileDoesNotExist() = runBlocking {
        val engine = LlamaCppEngine(File("/missing/model.gguf"), FakeNativeApi())
        try {
            val result = runCatching { engine.load() }
            assertTrue(result.isFailure)
            assertFalse(engine.isReady)
        } finally {
            engine.close()
        }
    }

    @Test
    fun loadsGeneratesAndReleasesNativeHandle() = runBlocking {
        val model = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(1024 * 1024)) }
        val native = FakeNativeApi()
        val engine = LlamaCppEngine(model, native, contextTokens = 2048, threads = 2)
        try {
            engine.load()
            assertTrue(engine.isReady)
            assertEquals("local result", engine.generate("classify task"))
            assertEquals(256, native.lastMaxTokens)
            engine.close()
            assertEquals(1, native.freeCount)
            assertFalse(engine.isReady)
        } finally {
            engine.close()
            model.delete()
        }
    }

    @Test
    fun rejectsInvalidContextAndThreadConfiguration() {
        val model = File("unused.gguf")
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), contextTokens = 512) }.isFailure)
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), threads = 0) }.isFailure)
    }

    private class FakeNativeApi : LlamaNativeApi {
        var freeCount = 0
        var lastMaxTokens = 0
        override fun load(modelPath: String, contextTokens: Int, threads: Int): Long = 42L
        override fun generate(handle: Long, prompt: String, maxTokens: Int): String {
            lastMaxTokens = maxTokens
            return "local result"
        }
        override fun free(handle: Long) { freeCount++ }
    }
}
