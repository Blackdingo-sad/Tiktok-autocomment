package com.example.app.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

internal object VietnameseVoskModel {
    private const val MODEL_NAME = "vosk-model-small-vn-0.4"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"

    fun directory(context: Context): File = File(context.noBackupFilesDir, MODEL_NAME)

    suspend fun ensureInstalled(
        context: Context,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val modelDirectory = directory(context)
        if (isUsable(modelDirectory)) return@withContext modelDirectory

        val zipFile = File(context.noBackupFilesDir, "$MODEL_NAME.zip.part")
        val stagingDirectory = File(context.noBackupFilesDir, "$MODEL_NAME.staging")
        zipFile.delete()
        stagingDirectory.deleteRecursively()

        try {
            val connection = URL(MODEL_URL).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "TikTokLiveAutoComment/1.0")
            }
            val contentLength = connection.contentLengthLong
            connection.getInputStream().use { input ->
                BufferedInputStream(input).use { bufferedInput ->
                    zipFile.outputStream().use { output ->
                        BufferedOutputStream(output).use { bufferedOutput ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                val count = bufferedInput.read(buffer)
                                if (count < 0) break
                                bufferedOutput.write(buffer, 0, count)
                                downloaded += count
                                if (contentLength > 0) {
                                    onProgress(((downloaded * 100) / contentLength).toInt().coerceIn(0, 99))
                                }
                            }
                        }
                    }
                }
            }

            val stagingPath = stagingDirectory.canonicalPath + File.separator
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val prefix = "$MODEL_NAME/"
                    if (!entry.name.startsWith(prefix)) {
                        zip.closeEntry()
                        continue
                    }

                    val relativePath = entry.name.removePrefix(prefix)
                    if (relativePath.isBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val outputFile = File(stagingDirectory, relativePath)
                    if (!outputFile.canonicalPath.startsWith(stagingPath)) {
                        throw IllegalStateException("Đường dẫn không hợp lệ trong model ASR")
                    }
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        outputFile.outputStream().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }

            check(isUsable(stagingDirectory)) { "Gói model tiếng Việt tải về không đầy đủ" }
            modelDirectory.deleteRecursively()
            check(stagingDirectory.renameTo(modelDirectory)) { "Không thể cài model ASR" }
            zipFile.delete()
            onProgress(100)
            modelDirectory
        } catch (error: Exception) {
            zipFile.delete()
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    private fun isUsable(directory: File): Boolean =
        File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile
}
