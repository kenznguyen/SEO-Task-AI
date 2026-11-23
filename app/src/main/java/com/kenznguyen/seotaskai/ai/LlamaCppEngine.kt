package com.kenznguyen.seotaskai.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.DisposableHandle
import java.io.File

data class NativeGenerationResult(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMillis: Long,
    val decodeMillis: Long
) {
    val decodeTokensPerSecond: Double
        get() = if (decodeMillis > 0) generatedTokens * 1000.0 / decodeMillis else 0.0
}

internal interface LlamaNativeApi {
    fun load(modelPath: String, contextTokens: Int, threads: Int): Long
    fun prepare(handle: Long)
    fun cancel(handle: Long)
    fun generate(handle: Long, prompt: String, maxTokens: Int): NativeGenerationResult
    fun free(handle: Long)
}

private object JniLlamaNativeApi : LlamaNativeApi {
    init {
        System.loadLibrary("seotaskai_llama")
    }

    override fun load(modelPath: String, contextTokens: Int, threads: Int) =
        NativeLlamaBindings.nativeLoad(modelPath, contextTokens, threads)

    override fun prepare(handle: Long) = NativeLlamaBindings.nativePrepare(handle)
    override fun cancel(handle: Long) = NativeLlamaBindings.nativeCancel(handle)

    override fun generate(handle: Long, prompt: String, maxTokens: Int) =
        NativeLlamaBindings.nativeGenerate(handle, prompt, maxTokens)

    override fun free(handle: Long) = NativeLlamaBindings.nativeFree(handle)
}

private object NativeLlamaBindings {
    external fun nativeLoad(modelPath: String, contextTokens: Int, threads: Int): Long
    @JvmStatic external fun nativePrepare(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): NativeGenerationResult
    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * CPU-first llama.cpp engine. The GGUF file must be in app-private storage.
 * Active inference is cancellable; close requests native cancellation and waits
 * for the active JNI call to exit before freeing the model/context.
 */
class LlamaCppEngine internal constructor(
    private val modelFile: File,
    private val native: LlamaNativeApi = JniLlamaNativeApi,
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = defaultThreadCount()
) : AIEngine {
    @Volatile private var nativeHandle: Long = 0L
    private var activeGenerations: Int = 0

    @Volatile var lastMetrics: NativeGenerationResult? = null
        private set
    @Volatile var loadTimeMillis: Long? = null
        private set

    init {
        require(contextTokens in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS) {
            "Context must be between $MIN_CONTEXT_TOKENS and $MAX_CONTEXT_TOKENS tokens"
        }
        require(threads in 1..MAX_THREADS) { "Thread count must be between 1 and $MAX_THREADS" }
    }

    override val isReady: Boolean
        get() = nativeHandle != 0L

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@LlamaCppEngine) {
            if (nativeHandle != 0L) return@synchronized
            require(modelFile.isFile && modelFile.canRead()) { "GGUF model file is missing or unreadable" }
            require(modelFile.extension.equals("gguf", ignoreCase = true)) { "Model must be a GGUF file" }
            require(modelFile.length() >= MIN_MODEL_BYTES) { "GGUF model file is unexpectedly small" }
            val loadStarted = System.nanoTime()
            val loaded = native.load(modelFile.absolutePath, contextTokens, threads)
            check(loaded != 0L) { "llama.cpp could not load the model" }
            loadTimeMillis = (System.nanoTime() - loadStarted) / 1_000_000L
            nativeHandle = loaded
        }
    }

    override suspend fun generate(prompt: String): String = generateWithMetrics(prompt).text

    @OptIn(InternalCoroutinesApi::class)
    suspend fun generateWithMetrics(prompt: String): NativeGenerationResult = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Prompt must not be empty" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "Prompt exceeds $MAX_PROMPT_CHARS characters" }
        val context = currentCoroutineContext()
        context.ensureActive()

        val handle: Long
        val cancellationHandler: DisposableHandle?
        synchronized(this@LlamaCppEngine) {
            handle = nativeHandle
            check(handle != 0L) { "Local model is not loaded" }
            native.prepare(handle)
            activeGenerations++
            cancellationHandler = context[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true
            ) { cause ->
                if (cause is CancellationException) native.cancel(handle)
            }
        }

        try {
            val result = try {
                native.generate(handle, prompt, MAX_GENERATION_TOKENS)
            } catch (error: Throwable) {
                context.ensureActive()
                throw error
            }
            context.ensureActive()
            lastMetrics = result
            result
        } finally {
            cancellationHandler?.dispose()
            synchronized(this@LlamaCppEngine) {
                activeGenerations--
                (this@LlamaCppEngine as java.lang.Object).notifyAll()
            }
        }
    }

    override fun close() {
        synchronized(this) {
            val handle = nativeHandle
            if (handle == 0L) return
            nativeHandle = 0L
            native.cancel(handle)
            var interrupted = false
            while (activeGenerations > 0) {
                try {
                    (this as java.lang.Object).wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            native.free(handle)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 2048
        const val MAX_GENERATION_TOKENS = 160
        private const val MIN_CONTEXT_TOKENS = 512
        private const val MAX_CONTEXT_TOKENS = 8192
        private const val MAX_THREADS = 8
        private const val MAX_PROMPT_CHARS = 16_000
        private const val MIN_MODEL_BYTES = 1L * 1024L * 1024L

        private fun defaultThreadCount() =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
