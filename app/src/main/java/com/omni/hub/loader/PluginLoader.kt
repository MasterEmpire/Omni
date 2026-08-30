package com.omni.hub.loader

import android.content.Context
import com.omni.hub.api.OmniLogger
import com.omni.hub.api.PluginEntry
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class LoadedPlugin(
    val id: String,
    val name: String,
    val entryClass: String,
    val instance: PluginEntry,
    val baseDir: File,
    val dataDir: File,
    val classLoader: ClassLoader
)

object PluginLoader {

    /**
     * Unpacks a bundle.zip into an isolated directory, parses the auto-discovery plugin.json,
     * enforces Android 14 DCL read-only security, and instantiates the PluginEntry class.
     */
    fun loadFromZip(
        context: Context,
        zipFile: File,
        fallbackPluginId: String? = null,
        fallbackEntryClass: String? = null
    ): LoadedPlugin {
        val tempUnpackDir = File(context.cacheDir, "unpack_${System.currentTimeMillis()}")
        tempUnpackDir.mkdirs()

        // 1. Unzip Bundle Archive to temp folder to read manifest
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(tempUnpackDir, entry.name)
                // Zip-Slip Security Protection
                if (!outFile.canonicalPath.startsWith(tempUnpackDir.canonicalPath)) {
                    throw SecurityException("Malicious zip entry path traversal: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                entry = zis.nextEntry
            }
        }

        val manifestFile = File(tempUnpackDir, PluginManifest.MANIFEST_FILE_NAME)
        val manifest = PluginManifest.parse(manifestFile)

        val finalId = manifest?.id ?: fallbackPluginId ?: "plugin_${System.currentTimeMillis()}"
        val finalClass = manifest?.entryClass ?: fallbackEntryClass
            ?: throw IllegalArgumentException("Missing plugin.json manifest and no fallback entry class provided.")
        val finalName = manifest?.name ?: finalId

        val targetBaseDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val pluginDir = File(targetBaseDir, finalId)

        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        tempUnpackDir.renameTo(pluginDir)

        val dexFile = File(pluginDir, "classes.dex")
        if (!dexFile.exists()) {
            throw IllegalArgumentException("Invalid plugin bundle: classes.dex missing from archive root.")
        }

        // 2. Android 14+ DCL Security Hardening: Make DEX file read-only
        dexFile.setReadOnly()

        // 3. Assemble Multi-DEX Path including persistent shared runtime packs
        val sharedPaths = SharedLibManager.getSharedDexPaths(context)
        val combinedDexPath = if (sharedPaths.isNotEmpty()) {
            (listOf(dexFile.absolutePath) + sharedPaths).joinToString(File.pathSeparator)
        } else {
            dexFile.absolutePath
        }

        OmniLogger.log("LOADER", "Initializing DexClassLoader with classpath: $combinedDexPath")

        val optDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
        if (!optDir.exists()) optDir.mkdirs()

        val dataBaseDir = context.getDir("plugins_data", Context.MODE_PRIVATE)
        val dataDir = File(dataBaseDir, finalId).apply { if (!exists()) mkdirs() }

        val loader = DexClassLoader(
            combinedDexPath,
            optDir.absolutePath,
            null,
            context.classLoader
        )

        val clazz = loader.loadClass(finalClass)
        val instance = clazz.getDeclaredConstructor().newInstance() as? PluginEntry
            ?: throw ClassCastException("Class $finalClass does not extend PluginEntry contract.")

        return LoadedPlugin(
            id = finalId,
            name = finalName,
            entryClass = finalClass,
            instance = instance,
            baseDir = pluginDir,
            dataDir = dataDir,
            classLoader = loader
        )
    }

    /**
     * Loads a pre-extracted plugin directory directly from storage with auto-manifest detection.
     */
    fun loadFromDir(
        context: Context,
        pluginId: String,
        fallbackEntryClass: String? = null
    ): LoadedPlugin {
        val targetBaseDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val pluginDir = File(targetBaseDir, pluginId)
        val dexFile = File(pluginDir, "classes.dex")

        if (!dexFile.exists()) {
            throw IllegalArgumentException("classes.dex missing in ${pluginDir.absolutePath}")
        }

        dexFile.setReadOnly()

        val manifestFile = File(pluginDir, PluginManifest.MANIFEST_FILE_NAME)
        val manifest = PluginManifest.parse(manifestFile)
        val finalClass = manifest?.entryClass ?: fallbackEntryClass
            ?: throw IllegalArgumentException("Cannot resolve entry class for plugin [$pluginId].")
        val finalName = manifest?.name ?: pluginId

        val optDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
        if (!optDir.exists()) optDir.mkdirs()

        val sharedPaths = SharedLibManager.getSharedDexPaths(context)
        val combinedDexPath = if (sharedPaths.isNotEmpty()) {
            (listOf(dexFile.absolutePath) + sharedPaths).joinToString(File.pathSeparator)
        } else {
            dexFile.absolutePath
        }

        OmniLogger.log("LOADER", "Loading directory plugin [$pluginId] with classpath: $combinedDexPath")

        val dataBaseDir = context.getDir("plugins_data", Context.MODE_PRIVATE)
        val dataDir = File(dataBaseDir, pluginId).apply { if (!exists()) mkdirs() }

        val loader = DexClassLoader(
            combinedDexPath,
            optDir.absolutePath,
            null,
            context.classLoader
        )

        val clazz = loader.loadClass(finalClass)
        val instance = clazz.getDeclaredConstructor().newInstance() as? PluginEntry
            ?: throw ClassCastException("Class $finalClass does not extend PluginEntry contract.")

        return LoadedPlugin(
            id = pluginId,
            name = finalName,
            entryClass = finalClass,
            instance = instance,
            baseDir = pluginDir,
            dataDir = dataDir,
            classLoader = loader
        )
    }
}