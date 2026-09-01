package com.omni.plugin.browser.services

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.ActiveDownloadItem
import com.omni.plugin.browser.utils.buildBlobExtractionScript
import java.io.File
import java.io.FileOutputStream

class DownloadController(
    private val context: Context,
    private val bridge: HostBridge
) {
    fun fetchCompletedDownloads(): List<File> {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloads")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun queryActiveDownloads(trackedIds: List<Long>): Pair<List<ActiveDownloadItem>, List<Long>> {
        if (trackedIds.isEmpty()) return Pair(emptyList(), emptyList())
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return Pair(emptyList(), emptyList())

        val updated = mutableListOf<ActiveDownloadItem>()
        val finishedIds = mutableListOf<Long>()

        try {
            val query = DownloadManager.Query().setFilterById(*trackedIds.toLongArray())
            val cursor = dm.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                do {
                    val dId = if (idCol >= 0) cursor.getLong(idCol) else -1L
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "Download" else "Download"
                    val bytes = if (bytesCol >= 0) cursor.getLong(bytesCol) else 0L
                    val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                    val status = if (statusCol >= 0) cursor.getInt(statusCol) else DownloadManager.STATUS_RUNNING
                    val prog = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        finishedIds.add(dId)
                    } else {
                        updated.add(ActiveDownloadItem(dId, title, bytes, total, prog, status))
                    }
                } while (cursor.moveToNext())
                cursor.close()
            }
        } catch (_: Exception) {}

        return Pair(updated, finishedIds)
    }

    fun saveBase64ToDownloads(base64Data: String, mimeType: String, rawFilename: String): String {
        bridge.log("DOWNLOAD", "Processing Base64 payload (mime: '$mimeType', rawFilename: '$rawFilename', len: ${base64Data.length})")
        try {
            val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            bridge.log("DOWNLOAD", "Decoded ${bytes.size} bytes from payload.")

            val ext = when {
                mimeType.contains("pdf") -> "pdf"
                mimeType.contains("png") -> "png"
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                mimeType.contains("zip") -> "zip"
                mimeType.contains("json") -> "json"
                mimeType.contains("html") -> "html"
                mimeType.contains("javascript") || mimeType.contains("js") -> "js"
                mimeType.contains("css") -> "css"
                mimeType.contains("text") -> "txt"
                else -> "bin"
            }

            var filename = rawFilename.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (filename.isEmpty() || filename == "null" || filename == "blob" || filename == "download") {
                filename = "download_${System.currentTimeMillis()}.$ext"
            } else if (!filename.contains(".")) {
                filename = "$filename.$ext"
            }

            var saved = false
            var savedLocation = ""

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifEmpty { "application/octet-stream" })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniDownloads")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(bytes)
                        }
                        saved = true
                        savedLocation = "Downloads/OmniDownloads/$filename (MediaStore)"
                        bridge.log("DOWNLOAD", "Saved via MediaStore -> $uri ($savedLocation)")
                    } else {
                        bridge.log("DOWNLOAD_WARN", "MediaStore returned null URI. Falling back to direct filesystem write.")
                    }
                } catch (e: Exception) {
                    bridge.log("DOWNLOAD_WARN", "MediaStore insert failed: ${e.message}. Falling back to direct File write.")
                }
            }

            if (!saved) {
                val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(publicDocs, "OmniDownloads").apply { if (!exists()) mkdirs() }
                val targetFile = File(targetDir, filename)
                FileOutputStream(targetFile).use { it.write(bytes) }
                saved = true
                savedLocation = targetFile.absolutePath
                bridge.log("DOWNLOAD", "Saved directly to File -> $savedLocation (${targetFile.length()} bytes)")
            }

            bridge.showToast("Saved $filename to Downloads/OmniDownloads")
            bridge.log("DOWNLOAD_SUCCESS", "✅ Download complete: $filename (${bytes.size} bytes)")
            return filename
        } catch (e: Exception) {
            bridge.showToast("Blob save failed: ${e.message}")
            bridge.log("DOWNLOAD_ERR", "Failed saving base64 file: ${e.message}\n${e.stackTraceToString()}")
            return ""
        }
    }

    fun triggerFileDownload(
        view: WebView?,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        onTrackId: (Long) -> Unit
    ) {
        bridge.log("DOWNLOAD", "triggerFileDownload invoked for: ${if (url.length > 40) url.take(40) + "..." else url} (mime: '$mimeType')")

        if (url.startsWith("blob:") || url.startsWith("data:")) {
            val suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("data:")) {
                saveBase64ToDownloads(url, mimeType, suggestedName)
            } else {
                val safeName = suggestedName.replace("'", "\\'").replace("\"", "\\\"")
                val safeMime = mimeType.replace("'", "\\'").replace("\"", "\\\"")
                val blobScript = buildBlobExtractionScript(url, safeName, safeMime)
                view?.evaluateJavascript(blobScript, null)
                bridge.showToast("Extracting Blob download...")
            }
            return
        }

        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (mimeType.isNotEmpty() && mimeType != "application/octet-stream") {
                    setMimeType(mimeType)
                }
                addRequestHeader("User-Agent", userAgent)
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookie)
                }
                setDescription("Downloading file...")
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            val dlId = dm?.enqueue(request)
            if (dlId != null) {
                onTrackId(dlId)
            }
            bridge.showToast("Downloading $filename...")
            bridge.log("DOWNLOAD", "Queued system download for $url ($filename) [ID: $dlId]")
        } catch (e: Exception) {
            bridge.showToast("Download error: ${e.message}")
            bridge.log("DOWNLOAD_ERR", "Download queue exception: ${e.message}")
        }
    }
}