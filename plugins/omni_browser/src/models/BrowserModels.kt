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
    val localSourcePath: String? = null,
    val isDefault: Boolean = false
)

data class SmartNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class BrowserProfile(
    val id: String,
    val name: String,
    val colorValue: Long
)

val PROFILE_PALETTE = listOf(
    0xFF2979FF, // Electric Royal Blue
    0xFF00E676, // Neon Emerald Green
    0xFFFF1744, // Vivid Crimson Red
    0xFFD500F9, // Electric Magenta Violet
    0xFFFFD600, // Vibrant Sun Gold
    0xFF00E5FF, // Cyberpunk Cyan
    0xFFFF6D00, // Deep Blaze Orange
    0xFFFF4081  // Hot Fuchsia Pink
)

data class BrowserTab(
    val id: String,
    val title: String = "New Tab",
    val url: String = "about:blank",
    val stateBundle: Bundle? = null,
    val thumbnail: Bitmap? = null,
    val lastAccessedTime: Long = System.currentTimeMillis(),
    val profileId: String = "default",
    val isDesktop: Boolean = false
)

class OmniMediaBridge(
    private val onMediaStateChanged: (title: String, artist: String, isPlaying: Boolean) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit
) {
    @JavascriptInterface
    fun reportMediaState(title: String, artist: String, isPlaying: Boolean) {
        onMediaStateChanged(title, artist, isPlaying)
    }

    @JavascriptInterface
    fun log(tag: String, message: String) {
        onLog(tag, message)
    }
}

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

data class SequentialPromptStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val prompt: String = "",
    val repeatCount: Int = 1,
    val isInfinite: Boolean = false,
    val attachments: List<AutomationAttachment> = emptyList()
)