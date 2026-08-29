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
        name: String? = null,
        entryClass: String? = null,
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
            val loaded = PluginLoader.loadFromZip(context, tempFile, name, entryClass)

            // Register in metadata registry
            val currentList = PluginManager.getInstalledPlugins(context).filter { it.id != loaded.id }.toMutableList()
            currentList.add(
                PluginMetadata(
                    id = loaded.id,
                    name = loaded.name,
                    description = description,
                    entryClass = loaded.entryClass,
                    installedAt = System.currentTimeMillis()
                )
            )
            PluginManager.saveRegistry(context, currentList)

            loaded
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}