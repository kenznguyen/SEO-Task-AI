package com.kenznguyen.seotaskai.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ImportedModel(
    val file: File,
    val sizeBytes: Long,
    val sha256: String
)

object GgufFileValidator {
    private val magic = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    const val MIN_MODEL_BYTES = 1L * 1024 * 1024

    fun validateHeader(header: ByteArray, sizeBytes: Long) {
        require(sizeBytes >= MIN_MODEL_BYTES) { "GGUF model file is unexpectedly small" }
        require(header.size >= magic.size && magic.indices.all { header[it] == magic[it] }) {
            "File does not have a valid GGUF header"
        }
    }

    fun hasEnoughSpace(availableBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes > 0 && availableBytes >= expectedBytes + (expectedBytes / 20)
}

/** Copies a user-selected document into app-private files; no broad storage permission is needed. */
class GgufModelManager(
    private val contentResolver: ContentResolver,
    private val modelDirectory: File
) {
    fun listModels(): List<File> = modelDirectory.listFiles()
        ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun import(uri: Uri, displayName: String): ImportedModel {
        require(displayName.endsWith(".gguf", ignoreCase = true)) { "Select a .gguf model file" }
        if (!modelDirectory.exists()) check(modelDirectory.mkdirs()) { "Cannot create private model directory" }
        val stat = StatFs(modelDirectory.absolutePath)
        val expected = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (expected > 0) {
            require(GgufFileValidator.hasEnoughSpace(stat.availableBytes, expected)) {
                "Not enough free storage for this model"
            }
        }

        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelDirectory, safeName)
        val temporary = File(modelDirectory, "$safeName.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val header = ByteArray(4)
                    var headerCount = 0
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (headerCount < 4) {
                            val copy = minOf(4 - headerCount, count)
                            System.arraycopy(buffer, 0, header, headerCount, copy)
                            headerCount += copy
                        }
                        total += count
                        require(total <= MAX_MODEL_BYTES) { "Model exceeds the 8 GB import limit" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    GgufFileValidator.validateHeader(header, total)
                }
            } ?: error("Cannot open selected model file")
            check(temporary.renameTo(destination)) { "Cannot finalize imported model" }
            return ImportedModel(destination, total, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    fun delete(file: File) {
        require(file.parentFile?.canonicalFile == modelDirectory.canonicalFile) {
            "Only models in the private model directory can be deleted"
        }
        check(file.delete()) { "Could not delete model" }
    }

    companion object {
        const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
    }
}
