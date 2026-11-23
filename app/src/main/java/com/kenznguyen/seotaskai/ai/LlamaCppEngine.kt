package com.kenznguyen.seotaskai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal interface LlamaNativeApi {
    fun load(modelPath: String, contextTokens: Int, threads: Int): Long
    fun generate(handle: Long, prompt: String, maxTokens: Int): String
    fun free(handle: Long)
}

private object JniLlamaNativeApi : LlamaNativeApi {
    init {
        System.loadLibrary("seotaskai_llama")
    }

    override fun load(modelPath: String, contextTokens: Int, threads: Int) =
        NativeLlamaBindings.nativeLoad(modelPath, contextTokens, threads)

    override fun generate(handle: Long, prompt: String, maxTokens: Int) =
        NativeLlamaBindings.nativeGenerate(handle, prompt, maxTokens)

    override fun free(handle: Long) = NativeLlamaBindings.nativeFree(handle)
}

private object NativeLlamaBindings {
    @JvmStatic external fun nativeLoad(modelPath: String, contextTokens: Int, threads: Int): Long
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * CPU-first llama.cpp engine. The GGUF file must be in app-private storage.
 * Native calls run on Dispatchers.IO and are serialized with close/load.
 */
class LlamaCppEngine internal constructor(
    private val modelFile: File,
    private val native: LlamaNativeApi = JniLlamaNativeApi,
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = defaultThreadCount()
) : AIEngine {
    @Volatile private var nativeHandle: Long = 0L

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
            val loaded = native.load(modelFile.absolutePath, contextTokens, threads)
            check(loaded != 0L) { "llama.cpp could not load the model" }
            nativeHandle = loaded
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Prompt must not be empty" }
        require(prompt.length <= MAX_PROMPT_CHARS) { "Prompt exceeds $MAX_PROMPT_CHARS characters" }
        synchronized(this@LlamaCppEngine) {
            val handle = nativeHandle
            check(handle != 0L) { "Local model is not loaded" }
            native.generate(handle, prompt, MAX_GENERATION_TOKENS)
        }
    }

    override fun close() {
        synchronized(this) {
            val handle = nativeHandle
            if (handle != 0L) {
                nativeHandle = 0L
                native.free(handle)
            }
        }
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 2048
        const val MAX_GENERATION_TOKENS = 256
        private const val MIN_CONTEXT_TOKENS = 512
        private const val MAX_CONTEXT_TOKENS = 8192
        private const val MAX_THREADS = 8
        private const val MAX_PROMPT_CHARS = 16_000
        private const val MIN_MODEL_BYTES = 1L * 1024L * 1024L

        private fun defaultThreadCount() =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
