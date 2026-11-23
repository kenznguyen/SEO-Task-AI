package com.kenznguyen.seotaskai.ai

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            assertTrue((engine.loadTimeMillis ?: -1L) >= 0L)
            assertEquals("local result", engine.generate("classify task"))
            assertEquals(17, engine.lastMetrics?.promptTokens)
            assertEquals(9, engine.lastMetrics?.generatedTokens)
            assertEquals(120L, engine.lastMetrics?.prefillMillis)
            assertEquals(300L, engine.lastMetrics?.decodeMillis)
            assertEquals(30.0, engine.lastMetrics?.decodeTokensPerSecond ?: 0.0, 0.001)
            assertEquals(160, native.lastMaxTokens)
            engine.close()
            assertEquals(1, native.freeCount)
            assertFalse(engine.isReady)
        } finally {
            engine.close()
            model.delete()
        }
    }

    @Test
    fun cancellationInterruptsActiveNativeGenerationAndWaitsForItToExit() = runBlocking {
        val model = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(1024 * 1024)) }
        val native = BlockingNativeApi()
        val engine = LlamaCppEngine(model, native, contextTokens = 2048, threads = 2)
        try {
            engine.load()
            val generation = launch { engine.generate("classify task") }
            assertTrue(native.generationStarted.await(2, TimeUnit.SECONDS))
            generation.cancelAndJoin()
            assertTrue(native.cancelRequested)
            assertEquals(0, native.activeGenerations)
        } finally {
            engine.close()
            model.delete()
        }
    }

    @Test
    fun closeCancelsActiveGenerationBeforeFreeingNativeHandle() = runBlocking {
        val model = File.createTempFile("model", ".gguf").apply { writeBytes(ByteArray(1024 * 1024)) }
        val native = BlockingNativeApi()
        val engine = LlamaCppEngine(model, native, contextTokens = 2048, threads = 2)
        try {
            engine.load()
            val generation = launch { runCatching { engine.generate("classify task") } }
            assertTrue(native.generationStarted.await(2, TimeUnit.SECONDS))
            engine.close()
            generation.join()
            assertTrue(native.cancelRequested)
            assertEquals(1, native.freeCount)
            assertEquals(0, native.activeGenerations)
        } finally {
            engine.close()
            model.delete()
        }
    }

    @Test
    fun reportsZeroDecodeRateWhenNoDecodeTimeWasMeasured() {
        val result = NativeGenerationResult("", 4, 0, 15L, 0L)
        assertEquals(0.0, result.decodeTokensPerSecond, 0.0)
    }

    @Test
    fun rejectsContextOutsideSupportedRangeAndInvalidThreadConfiguration() {
        val model = File("unused.gguf")
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), contextTokens = 511) }.isFailure)
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), contextTokens = 8193) }.isFailure)
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), threads = 0) }.isFailure)
        assertTrue(runCatching { LlamaCppEngine(model, FakeNativeApi(), threads = 9) }.isFailure)
    }

    private open class FakeNativeApi : LlamaNativeApi {
        var freeCount = 0
        var lastMaxTokens = 0
        override fun load(modelPath: String, contextTokens: Int, threads: Int): Long = 42L
        override fun prepare(handle: Long) = Unit
        override fun cancel(handle: Long) = Unit
        override fun generate(handle: Long, prompt: String, maxTokens: Int): NativeGenerationResult {
            lastMaxTokens = maxTokens
            return NativeGenerationResult("local result", 17, 9, 120L, 300L)
        }
        override fun free(handle: Long) { freeCount++ }
    }

    private class BlockingNativeApi : FakeNativeApi() {
        val generationStarted = CountDownLatch(1)
        @Volatile var cancelRequested = false
        @Volatile var activeGenerations = 0

        override fun cancel(handle: Long) { cancelRequested = true }

        override fun generate(handle: Long, prompt: String, maxTokens: Int): NativeGenerationResult {
            activeGenerations++
            generationStarted.countDown()
            try {
                while (!cancelRequested) Thread.sleep(5)
                throw IllegalStateException("Inference cancelled")
            } finally {
                activeGenerations--
            }
        }
    }
}
