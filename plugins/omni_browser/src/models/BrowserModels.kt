package com.omni.plugin.browser.models

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.JavascriptInterface

data class ShortcutItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val iconText: String = "",
    val colorValue: Long = 0xFF4285F4,
    val localSourcePath: String? = null
)

data class BrowserProfile(
    val id: String,
    val name: String,
    val colorValue: Long
)

val PROFILE_PALETTE = listOf(
    0xFF8AB4F8, // Blue (Default)
    0xFFC58AF9, // Purple (AI 2)
    0xFF81C995, // Emerald (AI 3)
    0xFFFDD663, // Amber
    0xFFF28B82, // Coral Rose
    0xFF78D9EC, // Cyan
    0xFFFFB74D, // Orange
    0xFFA7FFEB  // Mint
)

data class BrowserTab(
    val id: String,
    val title: String = "New Tab",
    val url: String = "about:blank",
    val stateBundle: Bundle? = null,
    val thumbnail: Bitmap? = null,
    val lastAccessedTime: Long = System.currentTimeMillis(),
    val profileId: String = "default"
)

class OmniBlobBridge(
    private val onBlobReceived: (base64Data: String, mimeType: String, filename: String) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit
) {
    @JavascriptInterface
    fun processBlob(base64Data: String, mimeType: String, filename: String) {
        onBlobReceived(base64Data, mimeType, filename)
    }

    @JavascriptInterface
    fun log(tag: String, message: String) {
        onLog(tag, message)
    }
}

data class ActiveDownloadItem(
    val downloadId: Long,
    val filename: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Float,
    val status: Int
)

data class SystemInstructionPreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AutomationAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64Data: String
)