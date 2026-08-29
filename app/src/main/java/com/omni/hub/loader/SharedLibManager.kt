package com.omni.hub.loader

import android.content.Context
import android.os.Environment
import com.omni.hub.api.OmniLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object SharedLibManager {
    private const val RUNTIME_DEX_NAME = "extended_icons.dex"
    private const val CLOUD_RUNTIME_URL = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/storage/v1/object/public/omni-modules/extended_icons.dex"
    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZsemdmYXFybnlpcWZ4eHh2dGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU1NTk5NDAsImV4cCI6MjA4MTEzNTk0MH0.y93d68JWyGL7NKXZEHLunAuayMEWw1K6yATFGLxkUxY"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Master persistent directory that survives app uninstallation.
     */
    private fun getPersistentVaultDir(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val vault = File(docsDir, ".omni_runtime")
        if (!vault.exists()) vault.mkdirs()
        return vault
    }

    /**
     * App-private execution directory (required for Android 14 read-only execution).
     */
    private fun getPrivateExecutionDir(context: Context): File {
        val dir = context.getDir("shared_libs", Context.MODE_PRIVATE)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Checks if the runtime pack is ready in private storage.
     * If not, checks the persistent vault to restore without network.
     * If missing in both, downloads silently from Supabase.
     */
    suspend fun ensureSharedRuntime(context: Context): Boolean = withContext(Dispatchers.IO) {
        val privateDex = File(getPrivateExecutionDir(context), RUNTIME_DEX_NAME)
        val persistentDex = File(getPersistentVaultDir(), RUNTIME_DEX_NAME)

        // 1. Check if already active in private execution storage
        if (privateDex.exists() && privateDex.length() > 0) {
            privateDex.setReadOnly()
            OmniLogger.log("RUNTIME", "Shared runtime extension pack verified in private sandbox (${privateDex.length()} bytes).")
            return@withContext true
        }

        // 2. Check persistent vault that survived app uninstall
        if (persistentDex.exists() && persistentDex.length() > 0) {
            try {
                OmniLogger.log("RUNTIME", "Found persistent runtime vault in Documents! Restoring to sandbox...")
                persistentDex.inputStream().use { input ->
                    FileOutputStream(privateDex).use { output ->
                        input.copyTo(output)
                    }
                }
                privateDex.setReadOnly()
                OmniLogger.log("RUNTIME", "✅ Restored shared runtime from persistent storage. Bypassed network download entirely!")
                return@withContext true
            } catch (e: Exception) {
                OmniLogger.log("RUNTIME_ERR", "Failed to copy from persistent vault: ${e.message}")
            }
        }

        // 3. Silent Cloud Download (One-Time Fetch)
        try {
            OmniLogger.log("RUNTIME", "Fetching shared runtime pack from cloud CDN...")
            val request = Request.Builder()
                .url(CLOUD_RUNTIME_URL)
                .addHeader("apikey", ANON_KEY)
                .addHeader("Authorization", "Bearer $ANON_KEY")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                OmniLogger.log("RUNTIME_WARN", "Runtime CDN returned HTTP ${response.code}. Will retry on next sync.")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val tempFile = File(context.cacheDir, "temp_runtime.dex")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Copy to private sandbox
            tempFile.inputStream().use { input ->
                FileOutputStream(privateDex).use { output ->
                    input.copyTo(output)
                }
            }
            privateDex.setReadOnly()

            // Save master copy to persistent vault that survives uninstallation
            try {
                tempFile.inputStream().use { input ->
                    FileOutputStream(persistentDex).use { output ->
                        input.copyTo(output)
                    }
                }
                OmniLogger.log("RUNTIME", "Vaulted master copy to ${persistentDex.absolutePath} (Survives uninstallation).")
            } catch (_: Exception) {}

            tempFile.delete()
            OmniLogger.log("RUNTIME", "✅ One-time shared runtime pack downloaded and activated (${privateDex.length()} bytes).")
            return@withContext true

        } catch (e: Exception) {
            OmniLogger.log("RUNTIME_ERR", "Silent runtime download failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Returns the full dexPath string containing any active shared runtime libraries.
     */
    fun getSharedDexPaths(context: Context): List<String> {
        val privateDex = File(getPrivateExecutionDir(context), RUNTIME_DEX_NAME)
        return if (privateDex.exists() && privateDex.length() > 0) {
            listOf(privateDex.absolutePath)
        } else {
            emptyList()
        }
    }
}