package com.omni.hub.loader

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class PluginMetadata(
    val id: String,
    val name: String,
    val description: String,
    val entryClass: String,
    val installedAt: Long
)

object PluginManager {
    private const val PREF_NAME = "omni_plugin_registry"
    private const val KEY_REGISTRY = "installed_plugins"

    fun getInstalledPlugins(context: Context): List<PluginMetadata> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REGISTRY, "[]") ?: "[]"
        val list = mutableListOf<PluginMetadata>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PluginMetadata(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "Dynamic Plugin"),
                        entryClass = obj.getString("entryClass"),
                        installedAt = obj.getLong("installedAt")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun installPluginFromUri(
        context: Context,
        uri: Uri,
        name: String? = null,
        entryClass: String? = null,
        description: String = "Imported Module"
    ): LoadedPlugin {
        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open content stream from URI: $uri")

        val loaded = try {
            PluginLoader.loadFromZip(context, tempFile, name, entryClass)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }

        // Register in persistent preferences
        val currentList = getInstalledPlugins(context).filter { it.id != loaded.id }.toMutableList()
        currentList.add(
            PluginMetadata(
                id = loaded.id,
                name = loaded.name,
                description = description,
                entryClass = loaded.entryClass,
                installedAt = System.currentTimeMillis()
            )
        )
        saveRegistry(context, currentList)

        return loaded
    }

    suspend fun installPluginFromUrl(
        context: Context,
        url: String,
        name: String? = null,
        entryClass: String? = null,
        description: String = "Remote OTA Plugin"
    ): LoadedPlugin {
        return RemotePluginDownloader.downloadAndInstall(context, url, name, entryClass, description)
    }

    fun deletePlugin(context: Context, pluginId: String) {
        val targetBaseDir = context.getDir("plugins", Context.MODE_PRIVATE)
        File(targetBaseDir, pluginId).deleteRecursively()

        val dataBaseDir = context.getDir("plugins_data", Context.MODE_PRIVATE)
        File(dataBaseDir, pluginId).deleteRecursively()

        val updated = getInstalledPlugins(context).filter { it.id != pluginId }
        saveRegistry(context, updated)
    }

    internal fun saveRegistry(context: Context, plugins: List<PluginMetadata>) {
        val arr = JSONArray()
        plugins.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("entryClass", p.entryClass)
                put("installedAt", p.installedAt)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGISTRY, arr.toString())
            .apply()
    }
}