package com.omni.hub.container

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omni.hub.MainActivity
import com.omni.hub.api.FilePickerDispatcher
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.OmniLogger
import com.omni.hub.api.PermissionDispatcher
import com.omni.hub.api.ScreenCaptureDispatcher
import com.omni.hub.loader.LoadedPlugin
import com.omni.hub.loader.OmniTaskManager
import com.omni.hub.loader.PluginLoader
import com.omni.hub.loader.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Standalone Document Activity hosting an individual dynamic plugin as its own OS Recents task.
 */
class PluginContainerActivity : ComponentActivity() {

    private var currentPluginId: String? = null
    private var currentSessionStartedAt: Long = 0L
    private var activeBridge: HostBridgeImpl? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var activePermissionCallback: ((Map<String, Boolean>) -> Unit)? = null
    private var activeFilePickerCallback: ((List<android.net.Uri>) -> Unit)? = null
    private var activeProjectionCallback: ((Int, Intent?) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        activePermissionCallback?.invoke(result)
        activePermissionCallback = null
    }

    private val singleFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        activeFilePickerCallback?.invoke(if (uri != null) listOf(uri) else emptyList())
        activeFilePickerCallback = null
    }

    private val multipleFilePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        activeFilePickerCallback?.invoke(uris)
        activeFilePickerCallback = null
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        activeProjectionCallback?.invoke(result.resultCode, result.data)
        activeProjectionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#0D1117"))

        registerDispatchers()
        setupBackDispatcher()

        mountPluginFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val pluginId = resolvePluginId(intent) ?: return
        val dexFile = File(getDir("plugins", Context.MODE_PRIVATE), "$pluginId/classes.dex")
        val isBytecodeNewer = dexFile.exists() && dexFile.lastModified() > currentSessionStartedAt

        if (pluginId != currentPluginId || isBytecodeNewer) {
            OmniLogger.log("CONTAINER", "Refreshing standalone window for [$pluginId] (bytecodeNewer=$isBytecodeNewer)")
            if (isBytecodeNewer) {
                PluginLoader.invalidate(pluginId)
            }
            mountPluginFromIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionDispatcher.setActiveOwner(this)
        FilePickerDispatcher.setActiveOwner(this)
        ScreenCaptureDispatcher.setActiveOwner(this)
        currentPluginId?.let { OmniTaskManager.onStandaloneActivityResumed(it, this) }
    }

    override fun onPause() {
        super.onPause()
        currentPluginId?.let { OmniTaskManager.onStandaloneActivityPaused(it, this) }
    }

    override fun onDestroy() {
        PermissionDispatcher.unregisterLauncher(this)
        FilePickerDispatcher.unregisterLauncher(this)
        ScreenCaptureDispatcher.unregisterLauncher(this)
        activityScope.cancel()

        if (isFinishing) {
            currentPluginId?.let { OmniTaskManager.onStandaloneActivityDestroyed(it, this) }
        }
        super.onDestroy()
    }

    private fun registerDispatchers() {
        PermissionDispatcher.registerLauncher(this) { perms, cb ->
            activePermissionCallback = cb
            permissionLauncher.launch(perms)
        }
        FilePickerDispatcher.registerLauncher(this) { mimeType, allowMultiple, cb ->
            activeFilePickerCallback = cb
            if (allowMultiple) {
                multipleFilePicker.launch(mimeType)
            } else {
                singleFilePicker.launch(mimeType)
            }
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ScreenCaptureDispatcher.registerLauncher(this) { cb ->
            activeProjectionCallback = cb
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled = activeBridge?.handleBackPressed() ?: false
                if (!handled) {
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun resolvePluginId(intent: Intent?): String? {
        return intent?.getStringExtra(EXTRA_PLUGIN_ID)
            ?: intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    private fun mountPluginFromIntent(intent: Intent?) {
        val pluginId = resolvePluginId(intent)
        if (pluginId.isNullOrEmpty()) {
            finishAndRemoveTask()
            return
        }

        if (!OmniTaskManager.isStandaloneWindowEnabled(this, pluginId)) {
            val hostIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("extra_open_plugin_id", pluginId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(hostIntent)
            finishAndRemoveTask()
            return
        }

        val installedMeta = PluginManager.getInstalledPlugins(this).find { it.id == pluginId }
        val entryClass = intent?.getStringExtra(EXTRA_ENTRY_CLASS) ?: installedMeta?.entryClass

        try {
            val loadedPlugin = PluginLoader.loadFromDir(this, pluginId, entryClass)
            val resolvedName = intent?.getStringExtra(EXTRA_PLUGIN_NAME) ?: installedMeta?.name ?: loadedPlugin.name
            val resolvedIcon = loadedPlugin.icon ?: installedMeta?.icon

            currentPluginId = loadedPlugin.id
            currentSessionStartedAt = System.currentTimeMillis()
            applyTaskDescription(loadedPlugin, resolvedName, resolvedIcon)

            val hostBridge = HostBridgeImpl(
                context = this,
                pluginDir = loadedPlugin.dataDir,
                pluginId = loadedPlugin.id,
                onWindowModeChanged = { enabled ->
                    OmniTaskManager.handleWindowModeTransition(
                        context = applicationContext,
                        pluginId = loadedPlugin.id,
                        pluginName = resolvedName,
                        entryClass = loadedPlugin.entryClass,
                        enabled = enabled
                    )
                }
            ) {
                OmniTaskManager.closeStandaloneWindow(this, loadedPlugin.id)
            }
            activeBridge = hostBridge

            val pluginView = loadedPlugin.instance.onCreateView(this, hostBridge, loadedPlugin.baseDir.absolutePath)
            (pluginView as? AbstractComposeView)?.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            }
            pluginView.apply {
                setViewTreeLifecycleOwner(this@PluginContainerActivity)
                setViewTreeViewModelStoreOwner(this@PluginContainerActivity)
                setViewTreeSavedStateRegistryOwner(this@PluginContainerActivity)
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
            }

            setContentView(pluginView)
            OmniTaskManager.registerStandaloneSession(this, loadedPlugin, pluginView, hostBridge)

        } catch (e: Exception) {
            OmniLogger.log("CONTAINER_ERR", "Failed launching standalone plugin [$pluginId]: ${e.message}")
            val errorLayout = FrameLayout(this).apply {
                val tv = TextView(this@PluginContainerActivity).apply {
                    text = "Failed to load plugin [$pluginId]:\n\n${e.message}\n\n${e.stackTraceToString()}"
                    setTextColor(Color.RED)
                    setPadding(40, 60, 40, 40)
                    textSize = 14f
                }
                addView(tv)
            }
            setContentView(errorLayout)
        }
    }

    private fun applyTaskDescription(loadedPlugin: LoadedPlugin, label: String, iconSource: String?) {
        val cleanIcon = iconSource?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val isEmojiOnly = !cleanIcon.isNullOrBlank() && cleanIcon.length <= 4 && !cleanIcon.contains("/") && !cleanIcon.contains(".")

        val initialBitmap: Bitmap = when {
            !cleanIcon.isNullOrBlank() && !isEmojiOnly && !cleanIcon.startsWith("http", true) -> {
                val localFile = File(loadedPlugin.baseDir, cleanIcon)
                if (localFile.exists() && localFile.isFile) {
                    BitmapFactory.decodeFile(localFile.absolutePath)
                } else null
            }
            !cleanIcon.isNullOrBlank() && cleanIcon.startsWith("http", true) -> {
                val cacheDir = getDir("omni_icon_cache", Context.MODE_PRIVATE)
                val cacheFile = File(cacheDir, "ico_" + abs(cleanIcon.hashCode()) + ".png")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else null
            }
            else -> null
        } ?: createBadgeBitmap(loadedPlugin.id, label, cleanIcon)

        setTaskCardVisual(label, initialBitmap)

        if (!cleanIcon.isNullOrBlank() && cleanIcon.startsWith("http", true)) {
            val cacheDir = getDir("omni_icon_cache", Context.MODE_PRIVATE)
            val cacheFile = File(cacheDir, "ico_" + abs(cleanIcon.hashCode()) + ".png")
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                activityScope.launch(Dispatchers.IO) {
                    try {
                        val req = Request.Builder().url(cleanIcon).build()
                        val resp = iconHttpClient.newCall(req).execute()
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                cacheFile.writeBytes(bytes)
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed) {
                                            setTaskCardVisual(label, bmp)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun setTaskCardVisual(label: String, iconBitmap: Bitmap) {
        try {
            @Suppress("DEPRECATION")
            setTaskDescription(
                ActivityManager.TaskDescription(
                    label,
                    iconBitmap,
                    Color.parseColor("#161B22")
                )
            )
        } catch (_: Exception) {}
    }

    private fun createBadgeBitmap(id: String, name: String, cleanIcon: String?): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val (startColor, endColor) = when {
            id.contains("browser") || name.contains("Chrome", true) -> Pair(0xFF1F6FEB.toInt(), 0xFF58A6FF.toInt())
            id.contains("ide") || name.contains("IDE", true) -> Pair(0xFF8957E5.toInt(), 0xFFBC8CFF.toInt())
            id.contains("scroll") -> Pair(0xFFDA3633.toInt(), 0xFFF85149.toInt())
            id.contains("tele") -> Pair(0xFF238636.toInt(), 0xFF3FB950.toInt())
            else -> {
                val hue = (abs(id.hashCode()) % 360).toFloat()
                Pair(
                    Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.72f)),
                    Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.70f, 0.85f))
                )
            }
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28f, 28f, bgPaint)

        val isEmojiOnly = !cleanIcon.isNullOrBlank() && cleanIcon.length <= 4 && !cleanIcon.contains("/") && !cleanIcon.contains(".")
        val symbol = when {
            isEmojiOnly -> cleanIcon!!
            id.contains("browser") || name.contains("Chrome", true) -> "🌐"
            id.contains("ide") || name.contains("IDE", true) -> "💻"
            id.contains("scroll") -> "🛡️"
            id.contains("tele") -> "📡"
            else -> "⚡"
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 62f
            textAlign = Paint.Align.CENTER
        }
        val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(symbol, size / 2f, yPos, textPaint)
        return bmp
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "extra_plugin_id"
        const val EXTRA_PLUGIN_NAME = "extra_plugin_name"
        const val EXTRA_ENTRY_CLASS = "extra_entry_class"

        private val iconHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}