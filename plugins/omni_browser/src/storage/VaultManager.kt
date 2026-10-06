package com.omni.plugin.browser.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.BrowserTab
import com.omni.plugin.browser.models.ShortcutItem
import com.omni.plugin.browser.utils.normalizeLocalFilePath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BrowserConfig(
    val apiKey: String = "",
    val autoSolve: Boolean = true,
    val forceDark: Boolean = false,
    val localPort: Int = 8080,
    val erudaEnabled: Boolean = false,
    val desktopMode: Boolean = false,
    val backgroundAudio: Boolean = true
)

data class VaultRestoreData(
    val profiles: List<BrowserProfile>? = null,
    val shortcuts: List<ShortcutItem>? = null,
    val tabs: List<BrowserTab>? = null,
    val activeTabId: String? = null,
    val selectedProfileId: String? = null,
    val solverApiKey: String? = null,
    val autoSolveEnabled: Boolean? = null,
    val systemPresets: List<com.omni.plugin.browser.models.SystemInstructionPreset>? = null,
    val smartNotes: List<com.omni.plugin.browser.models.SmartNote>? = null,
    val forceDark: Boolean? = null,
    val localPort: Int? = null
)

class VaultManager(
    private val context: Context,
    private val bridge: HostBridge
) {
    fun syncLocalFileToVault(sourcePath: String, targetSubPath: String = "ide/index.html"): Pair<Boolean, String> {
        val cleanInput = sourcePath.trim()
        bridge.log("IDE_SYNC", "Attempting sync for source: $cleanInput")

        // Prompt for All Files Access on Android 11+ if not yet granted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                bridge.showToast("🛡️ Please grant 'All files access' to sync local files")
            } catch (_: Exception) {}
        }

        try {
            var bytes: ByteArray? = null
            val cleanPath = normalizeLocalFilePath(cleanInput)

            if (cleanInput.startsWith("content://")) {
                val uri = Uri.parse(cleanInput)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        bytes = input.readBytes()
                    }
                } catch (secEx: Exception) {
                    bridge.log("IDE_SYNC_WARN", "Content URI direct stream failed: ${secEx.message}")
                    // Fallback: Resolve document ID to direct storage path
                    try {
                        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                            val docId = android.provider.DocumentsContract.getDocumentId(uri)
                            val rawPath = when {
                                docId.startsWith("raw:") -> docId.removePrefix("raw:")
                                docId.contains(":") -> {
                                    val parts = docId.split(":")
                                    if (parts[0].equals("primary", true)) "/storage/emulated/0/${parts[1]}" else null
                                }
                                else -> null
                            }
                            if (rawPath != null) {
                                val f = File(rawPath)
                                if (f.exists() && f.isFile) bytes = f.readBytes()
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            if (bytes == null) {
                val srcFile = File(cleanPath)
                if (srcFile.exists() && srcFile.isFile) {
                    try {
                        bytes = srcFile.readBytes()
                    } catch (ioEx: Exception) {
                        bridge.log("IDE_SYNC_WARN", "File read failed ($cleanPath): ${ioEx.message}")
                    }
                }

                if (bytes == null) {
                    try {
                        val uri = Uri.parse(if (cleanInput.startsWith("file://")) cleanInput else "file://$cleanPath")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            bytes = input.readBytes()
                        }
                    } catch (_: Exception) {}
                }

                if (bytes == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        val queryUri = android.provider.MediaStore.Files.getContentUri("external")
                        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
                        val selection = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
                        val args = arrayOf(cleanPath)
                        context.contentResolver.query(queryUri, projection, selection, args, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                val cUri = android.content.ContentUris.withAppendedId(queryUri, id)
                                context.contentResolver.openInputStream(cUri)?.use { input ->
                                    bytes = input.readBytes()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (bytes == null && !srcFile.exists()) {
                    val errMsg = "File not found at: $cleanPath"
                    bridge.log("IDE_SYNC_ERR", errMsg)
                    return Pair(false, errMsg)
                }
            }

            if (bytes != null && bytes!!.isNotEmpty()) {
                val savedAbsPath = bridge.saveFile(targetSubPath, bytes!!)
                val kb = String.format(Locale.US, "%.1f", bytes!!.size / 1024.0)
                bridge.log("IDE_SYNC", "✅ Vaulted $cleanInput -> $savedAbsPath ($kb KB)")
                return Pair(true, "file://$savedAbsPath")
            } else {
                val errMsg = "File is empty or unreadable"
                bridge.log("IDE_SYNC_ERR", "$errMsg: $cleanInput")
                return Pair(false, errMsg)
            }
        } catch (e: Exception) {
            val errMsg = "Sync error: ${e.message}"
            bridge.log("IDE_SYNC_ERR", "$errMsg on $cleanInput")
            return Pair(false, errMsg)
        }
    }

    fun autoMirrorVaultToDocuments() {
        Thread {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val vaultDir = File(docsDir, ".omni_vault").apply { mkdirs() }

                fun mirrorFile(relPath: String, outName: String) {
                    val src = File(bridge.getPluginDir(), relPath)
                    if (src.exists() && src.isFile) {
                        src.copyTo(File(vaultDir, outName), overwrite = true)
                    }
                }
                mirrorFile("config/profiles.json", "profiles.json")
                mirrorFile("config/shortcuts.json", "shortcuts.json")
                mirrorFile("config/session.json", "session.json")
                mirrorFile("config/solver.json", "solver.json")
                mirrorFile("config/system_presets.json", "system_presets.json")
                mirrorFile("config/smart_notes.json", "smart_notes.json")

                val ideDir = File(bridge.getPluginDir(), "ide")
                if (ideDir.exists() && ideDir.isDirectory) {
                    val destIdeDir = File(vaultDir, "ide").apply { mkdirs() }
                    ideDir.copyRecursively(destIdeDir, overwrite = true)
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun resurrectFromVault() {
        try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val vaultDir = File(docsDir, ".omni_vault")
            if (vaultDir.exists()) {
                fun restoreIfMissing(relPath: String, vaultName: String) {
                    if (bridge.readFile(relPath) == null) {
                        val vFile = File(vaultDir, vaultName)
                        if (vFile.exists() && vFile.isFile) {
                            bridge.saveFile(relPath, vFile.readBytes())
                        }
                    }
                }
                restoreIfMissing("config/profiles.json", "profiles.json")
                restoreIfMissing("config/shortcuts.json", "shortcuts.json")
                restoreIfMissing("config/session.json", "session.json")
                restoreIfMissing("config/solver.json", "solver.json")
                restoreIfMissing("config/system_presets.json", "system_presets.json")
                restoreIfMissing("config/smart_notes.json", "smart_notes.json")

                                    val vaultIdeDir = File(vaultDir, "ide")
                    if (vaultIdeDir.exists() && vaultIdeDir.isDirectory) {
                        bridge.log("BOOT_TRACE", "resurrectFromVault -> scanning vaultIdeDir: ${vaultIdeDir.absolutePath}")
                        var fileCount = 0
                        vaultIdeDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            fileCount++
                            val relPath = "ide/" + f.relativeTo(vaultIdeDir).path.replace("\\", "/")
                            if (bridge.readFile(relPath) == null) {
                                bridge.log("BOOT_TRACE", "resurrectFromVault -> copying file #$fileCount: $relPath (${f.length()} bytes)")
                                bridge.saveFile(relPath, f.readBytes())
                            }
                        }
                        bridge.log("BOOT_TRACE", "resurrectFromVault -> completed scanning vaultIdeDir ($fileCount files scanned)")
                    }
            }
        } catch (_: Exception) {}
    }

    fun saveShortcuts(list: List<ShortcutItem>) {
        try {
            val arr = JSONArray()
            list.forEach { s ->
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("url", s.url)
                    put("iconText", s.iconText)
                    put("colorValue", s.colorValue)
                    put("isDefault", s.isDefault)
                    if (!s.localSourcePath.isNullOrEmpty()) {
                        put("localSourcePath", s.localSourcePath)
                    }
                }
                arr.put(obj)
            }
            bridge.saveFile("config/shortcuts.json", arr.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadShortcuts(): List<ShortcutItem>? {
        return try {
            val bytes = bridge.readFile("config/shortcuts.json") ?: return null
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            val list = mutableListOf<ShortcutItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ShortcutItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        iconText = obj.optString("iconText", ""),
                        colorValue = obj.optLong("colorValue", 0xFF4285F4),
                        localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() },
                        isDefault = obj.optBoolean("isDefault", false)
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveProfiles(list: List<BrowserProfile>) {
        try {
            val arr = JSONArray()
            list.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("colorValue", p.colorValue)
                }
                arr.put(obj)
            }
            bridge.saveFile("config/profiles.json", arr.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadProfiles(): List<BrowserProfile>? {
        return try {
            val bytes = bridge.readFile("config/profiles.json") ?: return null
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            val list = mutableListOf<BrowserProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveSession(tabList: List<BrowserTab>, currentActiveId: String, selectedProfileId: String? = null) {
        try {
            val persistentTabs = tabList.filter { !it.id.startsWith("tab_landing_") }
            if (persistentTabs.isEmpty()) return

            val finalActiveId = if (persistentTabs.any { it.id == currentActiveId }) currentActiveId else persistentTabs.last().id
            val activeTab = persistentTabs.find { it.id == finalActiveId }
            val profileToSave = selectedProfileId ?: activeTab?.profileId

            val json = JSONObject()
            json.put("activeTabId", finalActiveId)
            if (!profileToSave.isNullOrEmpty()) {
                json.put("selectedProfileId", profileToSave)
            }
            val arr = JSONArray()
            persistentTabs.forEach { tab ->
                val tObj = JSONObject().apply {
                    put("id", tab.id)
                    put("title", tab.title)
                    put("url", tab.url)
                    put("profileId", tab.profileId)
                    put("lastAccessedTime", tab.lastAccessedTime)
                    put("isDesktop", tab.isDesktop)
                }
                arr.put(tObj)
            }
            json.put("tabs", arr)
            bridge.saveFile("config/session.json", json.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadSession(): Triple<List<BrowserTab>, String?, String?>? {
        return try {
            bridge.log("BOOT_TRACE", "loadSession -> reading config/session.json")
            val bytes = bridge.readFile("config/session.json")
            if (bytes == null) {
                bridge.log("BOOT_TRACE", "loadSession -> config/session.json is null")
                return null
            }
            bridge.log("BOOT_TRACE", "loadSession -> read ${bytes.size} bytes from config/session.json")
            val sObj = JSONObject(String(bytes, Charsets.UTF_8))
            val savedActiveId = sObj.optString("activeTabId", "").takeIf { it.isNotEmpty() }
            val savedProfileId = sObj.optString("selectedProfileId", "").takeIf { it.isNotEmpty() }
            val arr = sObj.optJSONArray("tabs") ?: return null
            bridge.log("BOOT_TRACE", "loadSession -> parsed JSON: activeTabId=$savedActiveId, tabsCount=${arr.length()}")
            val loadedTabs = mutableListOf<BrowserTab>()
            for (i in 0 until arr.length()) {
                val tObj = arr.getJSONObject(i)
                loadedTabs.add(
                    BrowserTab(
                        id = tObj.getString("id"),
                        title = tObj.optString("title", "New Tab"),
                        url = tObj.optString("url", "about:blank"),
                        lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                        profileId = tObj.optString("profileId", "default"),
                        isDesktop = tObj.optBoolean("isDesktop", false)
                    )
                )
            }
            if (loadedTabs.isEmpty()) null else Triple(loadedTabs, savedActiveId, savedProfileId)
        } catch (_: Exception) {
            null
        }
    }

    fun saveSolverConfig(
        apiKey: String,
        autoSolve: Boolean,
        forceDark: Boolean = false,
        localPort: Int = 8080,
        erudaEnabled: Boolean = false,
        desktopMode: Boolean = false,
        backgroundAudio: Boolean = true
    ) {
        try {
            val cfg = JSONObject().apply {
                put("apiKey", apiKey)
                put("autoSolve", autoSolve)
                put("forceDark", forceDark)
                put("localPort", localPort)
                put("erudaEnabled", erudaEnabled)
                put("desktopMode", desktopMode)
                put("backgroundAudio", backgroundAudio)
            }
            bridge.saveFile("config/solver.json", cfg.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadSolverConfig(): BrowserConfig? {
        return try {
            val bytes = bridge.readFile("config/solver.json") ?: return null
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            BrowserConfig(
                apiKey = json.optString("apiKey", ""),
                autoSolve = json.optBoolean("autoSolve", true),
                forceDark = json.optBoolean("forceDark", false),
                localPort = json.optInt("localPort", 8080),
                erudaEnabled = json.optBoolean("erudaEnabled", false),
                desktopMode = json.optBoolean("desktopMode", false),
                backgroundAudio = json.optBoolean("backgroundAudio", true)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveSmartNotes(list: List<com.omni.plugin.browser.models.SmartNote>) {
        try {
            val arr = JSONArray()
            list.forEach { n ->
                val obj = JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("content", n.content)
                    put("createdAt", n.createdAt)
                    put("updatedAt", n.updatedAt)
                }
                arr.put(obj)
            }
            bridge.saveFile("config/smart_notes.json", arr.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadSmartNotes(): List<com.omni.plugin.browser.models.SmartNote>? {
        return try {
            val bytes = bridge.readFile("config/smart_notes.json") ?: return null
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            val list = mutableListOf<com.omni.plugin.browser.models.SmartNote>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    com.omni.plugin.browser.models.SmartNote(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        content = obj.getString("content"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun saveSystemPresets(list: List<com.omni.plugin.browser.models.SystemInstructionPreset>) {
        try {
            val arr = JSONArray()
            list.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    put("body", p.body)
                    put("updatedAt", p.updatedAt)
                }
                arr.put(obj)
            }
            bridge.saveFile("config/system_presets.json", arr.toString().toByteArray(Charsets.UTF_8))
            autoMirrorVaultToDocuments()
        } catch (_: Exception) {}
    }

    fun loadSystemPresets(): List<com.omni.plugin.browser.models.SystemInstructionPreset>? {
        return try {
            val bytes = bridge.readFile("config/system_presets.json") ?: return null
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            val list = mutableListOf<com.omni.plugin.browser.models.SystemInstructionPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    com.omni.plugin.browser.models.SystemInstructionPreset(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        body = obj.getString("body"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun exportFullBackup(): File {
        CookieManager.getInstance().flush()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupFilename = "OmniBrowser_Backup_$timestamp.zip"
        val tempZipFile = File(context.cacheDir, backupFilename)

        ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
            fun addFileToZip(relativePath: String, entryName: String) {
                val file = File(bridge.getPluginDir(), relativePath)
                if (file.exists() && file.isFile) {
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            addFileToZip("config/profiles.json", "profiles.json")
            addFileToZip("config/shortcuts.json", "shortcuts.json")
            addFileToZip("config/session.json", "session.json")
            addFileToZip("config/solver.json", "solver.json")
            addFileToZip("config/system_presets.json", "system_presets.json")
            addFileToZip("config/smart_notes.json", "smart_notes.json")

            val ideDir = File(bridge.getPluginDir(), "ide")
            if (ideDir.exists() && ideDir.isDirectory) {
                ideDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val relPath = "ide/" + f.relativeTo(ideDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(relPath))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val targetDir = File(docsDir, "OmniBackups").apply { mkdirs() }
        val targetFile = File(targetDir, backupFilename)
        tempZipFile.copyTo(targetFile, overwrite = true)
        tempZipFile.delete()

        bridge.log("BACKUP", "Created backup: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        return targetFile
    }

    fun restoreFromBackup(uri: Uri): VaultRestoreData {
        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Cannot read backup file.")

        var loadedProfiles: List<BrowserProfile>? = null
        var loadedShortcuts: List<ShortcutItem>? = null
        var loadedTabs: List<BrowserTab>? = null
        var loadedActiveTabId: String? = null
        var loadedSelectedProfileId: String? = null
        var loadedApiKey: String? = null
        var loadedAutoSolve: Boolean? = null
        var loadedForceDark: Boolean? = null
        var loadedLocalPort: Int? = null
        var loadedSystemPresets: List<com.omni.plugin.browser.models.SystemInstructionPreset>? = null
        var loadedSmartNotes: List<com.omni.plugin.browser.models.SmartNote>? = null

        ZipInputStream(tempFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val bytes = zis.readBytes()
                if (entry.name.startsWith("ide/")) {
                    bridge.saveFile(entry.name, bytes)
                } else {
                    when (entry.name) {
                        "profiles.json" -> {
                            bridge.saveFile("config/profiles.json", bytes)
                            val arr = JSONArray(String(bytes, Charsets.UTF_8))
                            val profs = mutableListOf<BrowserProfile>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                profs.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
                            }
                            if (profs.isNotEmpty()) loadedProfiles = profs
                        }
                        "shortcuts.json" -> {
                            bridge.saveFile("config/shortcuts.json", bytes)
                            val arr = JSONArray(String(bytes, Charsets.UTF_8))
                            val scs = mutableListOf<ShortcutItem>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                scs.add(
                                    ShortcutItem(
                                        id = obj.optString("id", UUID.randomUUID().toString()),
                                        title = obj.getString("title"),
                                        url = obj.getString("url"),
                                        iconText = obj.optString("iconText", ""),
                                        colorValue = obj.optLong("colorValue", 0xFF4285F4),
                                        localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() },
                                        isDefault = obj.optBoolean("isDefault", false)
                                    )
                                )
                            }
                            if (scs.isNotEmpty()) loadedShortcuts = scs
                        }
                        "session.json" -> {
                            bridge.saveFile("config/session.json", bytes)
                            val sObj = JSONObject(String(bytes, Charsets.UTF_8))
                            loadedActiveTabId = sObj.optString("activeTabId", "").takeIf { it.isNotEmpty() }
                            loadedSelectedProfileId = sObj.optString("selectedProfileId", "").takeIf { it.isNotEmpty() }
                            val arr = sObj.optJSONArray("tabs")
                            if (arr != null && arr.length() > 0) {
                                val tList = mutableListOf<BrowserTab>()
                                for (i in 0 until arr.length()) {
                                    val tObj = arr.getJSONObject(i)
                                    tList.add(
                                        BrowserTab(
                                            id = tObj.getString("id"),
                                            title = tObj.optString("title", "New Tab"),
                                            url = tObj.optString("url", "about:blank"),
                                            lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                                            profileId = tObj.optString("profileId", "default"),
                                            isDesktop = tObj.optBoolean("isDesktop", false)
                                        )
                                    )
                                }
                                if (tList.isNotEmpty()) loadedTabs = tList
                            }
                        }
                        "solver.json" -> {
                            bridge.saveFile("config/solver.json", bytes)
                            val json = JSONObject(String(bytes, Charsets.UTF_8))
                            loadedApiKey = json.optString("apiKey", "")
                            loadedAutoSolve = json.optBoolean("autoSolve", true)
                            loadedForceDark = json.optBoolean("forceDark", false)
                            loadedLocalPort = json.optInt("localPort", 8080)
                        }
                        "system_presets.json" -> {
                            bridge.saveFile("config/system_presets.json", bytes)
                            val arr = JSONArray(String(bytes, Charsets.UTF_8))
                            val presets = mutableListOf<com.omni.plugin.browser.models.SystemInstructionPreset>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                presets.add(
                                    com.omni.plugin.browser.models.SystemInstructionPreset(
                                        id = obj.optString("id", UUID.randomUUID().toString()),
                                        title = obj.getString("title"),
                                        body = obj.getString("body"),
                                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                                    )
                                )
                            }
                            if (presets.isNotEmpty()) loadedSystemPresets = presets
                        }
                        "smart_notes.json" -> {
                            bridge.saveFile("config/smart_notes.json", bytes)
                            val arr = JSONArray(String(bytes, Charsets.UTF_8))
                            val notes = mutableListOf<com.omni.plugin.browser.models.SmartNote>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                notes.add(
                                    com.omni.plugin.browser.models.SmartNote(
                                        id = obj.optString("id", UUID.randomUUID().toString()),
                                        title = obj.getString("title"),
                                        content = obj.getString("content"),
                                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                                    )
                                )
                            }
                            if (notes.isNotEmpty()) loadedSmartNotes = notes
                        }
                        "ide_index.html" -> {
                            bridge.saveFile("ide/index.html", bytes)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        tempFile.delete()
        autoMirrorVaultToDocuments()

        return VaultRestoreData(
            profiles = loadedProfiles,
            shortcuts = loadedShortcuts,
            tabs = loadedTabs,
            activeTabId = loadedActiveTabId,
            selectedProfileId = loadedSelectedProfileId,
            solverApiKey = loadedApiKey,
            autoSolveEnabled = loadedAutoSolve,
            systemPresets = loadedSystemPresets,
            smartNotes = loadedSmartNotes,
            forceDark = loadedForceDark,
            localPort = loadedLocalPort
        )
    }
}