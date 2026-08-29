package com.omni.hub.loader

import android.content.Context
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
    val classLoader: ClassLoader
)

object PluginLoader {

    /**
     * Unpacks a bundle.zip into an isolated directory, enforces Android 14 DCL read-only security,
     * and instantiates the PluginEntry class.
     */
    fun loadFromZip(
        context: Context,
        pluginId: String,
        zipFile: File,
        entryClassName: String
    ): LoadedPlugin {
        val targetBaseDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val pluginDir = File(targetBaseDir, pluginId)

        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        pluginDir.mkdirs()

        // 1. Unzip Bundle Archive
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(pluginDir, entry.name)
                // Zip-Slip Security Protection
                if (!outFile.canonicalPath.startsWith(pluginDir.canonicalPath)) {
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

        val dexFile = File(pluginDir, "classes.dex")
        if (!dexFile.exists()) {
            throw IllegalArgumentException("Invalid plugin bundle: classes.dex missing from archive root.")
        }

        // 2. Android 14+ DCL Security Hardening: Make DEX file read-only
        dexFile.setReadOnly()

        // 3. Instantiate ClassLoader and Entry Instance
        val optDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
        if (!optDir.exists()) optDir.mkdirs()

        // Assemble Multi-DEX Path including persistent shared runtime packs
        val sharedPaths = SharedLibManager.getSharedDexPaths(context)
        val combinedDexPath = if (sharedPaths.isNotEmpty()) {
            (listOf(dexFile.absolutePath) + sharedPaths).joinToString(File.pathSeparator)
        } else {
            dexFile.absolutePath
        }

        com.omni.hub.api.OmniLogger.log("LOADER", "Initializing DexClassLoader with classpath: $combinedDexPath")

        val loader = DexClassLoader(
            combinedDexPath,
            optDir.absolutePath,
            null,
            context.classLoader
        )

        val clazz = loader.loadClass(entryClassName)
        val instance = clazz.getDeclaredConstructor().newInstance() as? PluginEntry
            ?: throw ClassCastException("Class $entryClassName does not extend PluginEntry contract.")

        return LoadedPlugin(
            id = pluginId,
            name = pluginId,
            entryClass = entryClassName,
            instance = instance,
            baseDir = pluginDir,
            classLoader = loader
        )
    }

    /**
     * Loads a pre-extracted plugin directory directly from storage.
     */
    fun loadFromDir(
        context: Context,
        pluginId: String,
        entryClassName: String
    ): LoadedPlugin {
        val targetBaseDir = context.getDir("plugins", Context.MODE_PRIVATE)
        val pluginDir = File(targetBaseDir, pluginId)
        val dexFile = File(pluginDir, "classes.dex")

        if (!dexFile.exists()) {
            throw IllegalArgumentException("classes.dex missing in ${pluginDir.absolutePath}")
        }

        dexFile.setReadOnly()
        val optDir = context.getDir("dex_opt", Context.MODE_PRIVATE)
        if (!optDir.exists()) optDir.mkdirs()

        // Assemble Multi-DEX Path including persistent shared runtime packs
        val sharedPaths = SharedLibManager.getSharedDexPaths(context)
        val combinedDexPath = if (sharedPaths.isNotEmpty()) {
            (listOf(dexFile.absolutePath) + sharedPaths).joinToString(File.pathSeparator)
        } else {
            dexFile.absolutePath
        }

        com.omni.hub.api.OmniLogger.log("LOADER", "Loading directory plugin [$pluginId] with classpath: $combinedDexPath")

        val loader = DexClassLoader(
            combinedDexPath,
            optDir.absolutePath,
            null,
            context.classLoader
        )

        val clazz = loader.loadClass(finalClass)
            ?: throw ClassCastException("Class $entryClassName does not extend PluginEntry contract.")

        return LoadedPlugin(
            id = pluginId,
            name = pluginId,
            entryClass = entryClassName,
            instance = instance,
            baseDir = pluginDir,
            classLoader = loader
        )
    }
}