package com.omni.hub.loader

import org.json.JSONObject
import java.io.File

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val entryClass: String,
    val description: String,
    val iconPath: String? = null,
    val permissions: List<String> = emptyList()
) {
    companion object {
        const val MANIFEST_FILE_NAME = "plugin.json"

        fun parse(jsonString: String): PluginManifest {
            val obj = JSONObject(jsonString)
            val permissionsList = mutableListOf<String>()
            val permArray = obj.optJSONArray("permissions")
            if (permArray != null) {
                for (i in 0 until permArray.length()) {
                    permissionsList.add(permArray.getString(i))
                }
            }

            return PluginManifest(
                id = obj.getString("id"),
                name = obj.getString("name"),
                version = obj.optString("version", "1.0.0"),
                entryClass = obj.getString("entryClass"),
                description = obj.optString("description", "Omni Hub Dynamic Module"),
                iconPath = obj.optString("icon", null),
                permissions = permissionsList
            )
        }

        fun parse(file: File): PluginManifest? {
            return if (file.exists() && file.isFile) {
                try {
                    parse(file.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                    null
                }
            } else null
        }
    }
}