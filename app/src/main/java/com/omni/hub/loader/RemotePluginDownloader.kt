package com.omni.hub.loader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object RemotePluginDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads a bundle.zip from a remote HTTPS URL and installs it into Omni Hub.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        name: String,
        entryClass: String,
        description: String = "Remote OTA Plugin"
    ): LoadedPlugin = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP Download failed with code ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body received from $url")
        val tempFile = File(context.cacheDir, "remote_${System.currentTimeMillis()}.zip")

        body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        try {
            val pluginId = name.replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
            val loaded = PluginLoader.loadFromZip(context, pluginId, tempFile, entryClass)

            // Register in metadata registry
            val currentList = PluginManager.getInstalledPlugins(context).filter { it.id != pluginId }.toMutableList()
            currentList.add(
                PluginMetadata(
                    id = pluginId,
                    name = name,
                    description = description,
                    entryClass = entryClass,
                    installedAt = System.currentTimeMillis()
                )
            )
            val saveMethod = PluginManager::class.java.getDeclaredMethod("saveRegistry", Context::class.java, List::class.java)
            saveMethod.isAccessible = true
            saveMethod.invoke(PluginManager, context, currentList)

            loaded
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}