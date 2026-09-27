package com.phoneagent.voice

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.FileProvider
import com.phoneagent.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Gemma 3n .task model file directly from Hugging Face into the
 * app's private storage, so the user does not need to manually download it in
 * a browser and pick it via the file picker.
 *
 * Default model: gemma-3n-E2B-it-int4.task
 * Source: https://huggingface.co/google/gemma-3n-E2B-it-litert-preview
 *
 * For auto-update: call checkAndAutoUpdate() on app start; it compares the
 * local file size against the remote size and offers to re-download if the
 * remote is newer/different.
 */
class GemmaModelDownloader(private val context: Context) {

    companion object {
        // Public HF resolve URL for the int4 task file.
        // If the exact filename changes, update this constant and MODEL_FILENAME below.
        private const val HF_BASE_URL = "https://huggingface.co"
        private const val HF_MODEL_ID = "google/gemma-3n-E2B-it-litert-preview"
        private const val MODEL_FILENAME = "gemma-3n-E2B-it-int4.task"
        private val HF_DOWNLOAD_URL = "$HF_BASE_URL/api/models/$HF_MODEL_ID/resolve/main/$MODEL_FILENAME"

        private const val DOWNLOAD_CHUNK_BYTES = 4 * 1024 * 1024L // 4 MB chunks
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    /**
     * Returns the absolute path to the local model file, or null if it does
     * not exist yet.
     */
    fun getLocalModelPath(): String? = modelFile.absolutePath.takeIf { modelFile.exists() }

    /**
     * Returns true if a model file is already present in app storage.
     */
    fun hasModel(): Boolean = modelFile.exists()

    /**
     * Returns the local file size in bytes, or -1 if not present.
     */
    fun localSizeBytes(): Long = modelFile.length()

    /**
     * Fetch the remote file size from Hugging Face without downloading the
     * whole file. Returns the size in bytes, or -1 if it cannot be determined.
     */
    suspend fun remoteSizeBytes(): Long = withContext(Dispatchers.IO) {
        try {
            val url = URL(HF_DOWNLOAD_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code in 200..299) {
                conn.getHeaderField("content-length")?.toLongOrNull() ?: -1L
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Download the model file from Hugging Face into app storage.
     *
     * @param onProgress Callback invoked periodically with (bytesDownloaded, totalBytes).
     *                   totalBytes may be -1 if the server did not report content-length.
     * @param onError     Called if the download fails.
     * @param onComplete  Called when the file has been fully written.
     */
    suspend fun download(
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val dest = modelFile
        // Delete partial file if present
        dest.delete()

        try {
            val url = URL(HF_DOWNLOAD_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "PhoneAgent/0.1")

            val code = conn.responseCode
            if (code !in 200..299) {
                onError("HTTP $code from Hugging Face")
                return@withContext
            }

            val totalBytes = conn.getHeaderField("content-length")?.toLongOrNull() ?: -1L
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES.toInt())
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }

            // Verify we actually wrote something
            if (!dest.exists() || dest.length() == 0L) {
                onError("Download completed but file is empty")
                dest.delete()
                return@withContext
            }

            onComplete()
        } catch (e: Exception) {
            dest.delete()
            onError("Download failed: ${e.message}")
        }
    }

    /**
     * Check whether the remote model differs from the local one (by file size),
     * and if so, download the updated version automatically.
     *
     * @return true if an update was performed, false if no update was needed or
     *         the check could not be completed.
     */
    suspend fun checkAndAutoUpdate(
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val remoteSize = remoteSizeBytes()
        if (remoteSize < 0) {
            onError("Could not check for model updates (network error)")
            return@withContext false
        }

        val localSize = localSizeBytes()
        if (localSize < 0) {
            // No local model yet — download fresh
            download(onProgress = onProgress, onError = onError) {
                onProgress(0, remoteSize) // signal completion
            }
            return@withContext hasModel()
        }

        if (remoteSize != localSize) {
            // Remote differs — download updated model
            download(onProgress = onProgress, onError = onError) {
                onProgress(0, remoteSize)
            }
            return@withContext hasModel()
        }

        false
    }

    /**
     * Delete the local model file to free space.
     */
    fun deleteModel() {
        modelFile.delete()
    }

    /**
     * Return a content:// URI for the model file so it can be shared with
     * other apps if needed (e.g. for debugging).
     */
    fun getModelUri(): Uri? = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            modelFile
        )
    } catch (e: Exception) {
        null
    }
}
