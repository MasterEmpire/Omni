package com.omni.hub.api

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The typed Host API provided by Omni Hub to all dynamic plugins.
 */
interface HostBridge {
    // --- UI & Lifecycle ---
    fun close()
    fun showRecents()
    fun showToast(message: String)
    fun copyToClipboard(text: String)
    fun getClipboardText(): String?
    fun vibrate(durationMs: Long)
    fun setOnBackPressedHandler(handler: (() -> Boolean)?)
    fun handleBackPressed(): Boolean
    fun pickFiles(mimeType: String = "*/*", allowMultiple: Boolean = false, onResult: (List<Uri>) -> Unit)

    // --- Permissions & Security ---
    fun hasPermission(permission: String): Boolean
    fun requestPermission(permission: String, onResult: (Boolean) -> Unit)
    fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit)

    // --- Device Diagnostics & Hardware Info ---
    fun getBatteryLevel(): Int
    fun isCharging(): Boolean
    fun getSystemInfo(): String
    fun getStorageStats(): Map<String, String>

    // --- Hardware Controls ---
    fun setScreenBrightness(percentage: Int)
    fun setVolume(stream: String, level: Int)
    fun getRingerMode(): Int

    // --- Connectivity & Network ---
    fun isNetworkAvailable(): Boolean
    fun getWifiStatus(): String
    fun httpGet(url: String): String?
    fun httpPost(url: String, jsonBody: String): String?

    // --- Sensors ---
    fun sampleSensors(): String

    // --- System Overlays & Floating Windows (Window Canvas) ---
    fun canDrawOverlays(): Boolean
    fun requestOverlayPermission()
    fun showOverlay(
        tag: String,
        view: View,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity: Int = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        x: Int = 0,
        y: Int = 0,
        focusable: Boolean = false,
        touchable: Boolean = true,
        autoDismissMs: Long = 0L
    ): Boolean
    fun dismissOverlay(tag: String): Boolean
    fun dismissAllOverlays()

    // --- Isolated File System ---
    fun getPluginDir(): String
    fun saveFile(relativePath: String, content: ByteArray): String
    fun readFile(relativePath: String): ByteArray?
    fun listFiles(relativePath: String): List<String>
    fun deleteFile(relativePath: String): Boolean

    // --- Intents & System Execution ---
    fun launchApp(packageName: String): Boolean
    fun runIntent(action: String, dataUri: String?, extras: Map<String, Any>?): Boolean
    fun executeShell(cmd: String): String

    // --- Background & Power Management ---
    fun acquireWakeLock(tag: String = "OmniAutomation")
    fun releaseWakeLock()
    fun startForegroundTask(title: String, message: String)
    fun updateForegroundTask(message: String)
    fun stopForegroundTask()

    // --- Background Media Playback ---
    fun startMediaPlayback(title: String, artist: String, isPlaying: Boolean, onAction: (Boolean) -> Unit)
    fun updateMediaPlayback(title: String, artist: String, isPlaying: Boolean)
    fun stopMediaPlayback()

    // --- Audio & Media Projection ---
    fun requestMediaProjection(onResult: (resultCode: Int, data: Intent?) -> Unit)
    fun startProjectionService(
        resultCode: Int,
        data: Intent,
        title: String,
        message: String,
        onReady: (android.media.projection.MediaProjection?) -> Unit
    )
    fun stopProjectionService()
    fun getMediaProjectionManager(): android.media.projection.MediaProjectionManager

    // --- Logging & Diagnostics ---
    fun log(tag: String, message: String)
    fun showDiagnostics()

    // --- Python Engine Bridge (Nexus Microkernel) ---
    fun isPythonEngineAvailable(): Boolean
    fun executePython(
        code: String,
        onOutput: (String) -> Unit = {},
        onComplete: (success: Boolean, output: String) -> Unit
    )
}

object PermissionDispatcher {
    private var launcher: ((Array<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null

    fun registerLauncher(block: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit) {
        launcher = block
    }

    fun request(permissions: Array<String>, callback: (Map<String, Boolean>) -> Unit) {
        val l = launcher
        if (l != null) {
            Handler(Looper.getMainLooper()).post {
                l(permissions, callback)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                callback(permissions.associateWith { false })
            }
        }
    }
}

object FilePickerDispatcher {
    private var launcher: ((String, Boolean, (List<Uri>) -> Unit) -> Unit)? = null

    fun registerLauncher(block: (String, Boolean, (List<Uri>) -> Unit) -> Unit) {
        launcher = block
    }

    fun pick(mimeType: String, allowMultiple: Boolean, callback: (List<Uri>) -> Unit) {
        val l = launcher
        if (l != null) {
            Handler(Looper.getMainLooper()).post {
                l(mimeType, allowMultiple, callback)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                callback(emptyList())
            }
        }
    }
}

object MediaPlaybackDispatcher {
    @Volatile
    private var listener: ((Boolean) -> Unit)? = null

    fun registerListener(cb: ((Boolean) -> Unit)?) {
        listener = cb
    }

    fun onAction(shouldPlay: Boolean) {
        Handler(Looper.getMainLooper()).post {
            listener?.invoke(shouldPlay)
        }
    }
}

object ScreenCaptureDispatcher {
    private var launcher: (((Int, Intent?) -> Unit) -> Unit)? = null

    fun registerLauncher(block: (((Int, Intent?) -> Unit) -> Unit)?) {
        launcher = block
    }

    fun requestCapture(callback: (Int, Intent?) -> Unit) {
        val l = launcher
        if (l != null) {
            Handler(Looper.getMainLooper()).post {
                l(callback)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                callback(android.app.Activity.RESULT_CANCELED, null)
            }
        }
    }
}

object MediaProjectionDispatcher {
    @Volatile
    private var callback: ((android.media.projection.MediaProjection?) -> Unit)? = null

    fun registerCallback(cb: ((android.media.projection.MediaProjection?) -> Unit)?) {
        callback = cb
    }

    fun dispatchProjectionReady(projection: android.media.projection.MediaProjection?) {
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(projection)
            callback = null
        }
    }
}

object RecentsDispatcher {
    private var launcher: (() -> Unit)? = null

    fun registerLauncher(block: (() -> Unit)?) {
        launcher = block
    }

    fun showRecents() {
        Handler(Looper.getMainLooper()).post {
            launcher?.invoke()
        }
    }
}

object DiagnosticsDispatcher {
    private var launcher: (() -> Unit)? = null

    fun registerLauncher(block: (() -> Unit)?) {
        launcher = block
    }

    fun showDiagnostics() {
        Handler(Looper.getMainLooper()).post {
            launcher?.invoke()
        }
    }
}

/**
 * Client-side IPC controller connecting Omni Hub to the Nexus Python Engine.
 */
object NexusPythonClient {
    private const val NEXUS_PACKAGE = "com.conduit.nexus"
    private const val BIND_ACTION = "com.conduit.nexus.action.BIND_PYTHON_BRIDGE"

    private const val MSG_EXECUTE_CODE = 100
    private const val MSG_STREAM_OUTPUT = 101
    private const val MSG_EXECUTION_SUCCESS = 102
    private const val MSG_EXECUTION_ERROR = 103

    private const val KEY_CODE = "key_code"
    private const val KEY_EXEC_ID = "key_exec_id"
    private const val KEY_OUTPUT_CHUNK = "key_chunk"
    private const val KEY_FINAL_RESULT = "key_result"
    private const val KEY_ERROR_MESSAGE = "key_error"

    @Volatile private var serviceMessenger: android.os.Messenger? = null
    @Volatile private var isBound = false

    private data class RequestCallbacks(
        val onOutput: (String) -> Unit,
        val onComplete: (Boolean, String) -> Unit
    )

    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, RequestCallbacks>()
    private val readyQueue = java.util.Collections.synchronizedList(mutableListOf<(android.os.Messenger) -> Unit>())

    private val clientMessenger = android.os.Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: android.os.Message) {
            val bundle = msg.data ?: return
            val execId = bundle.getString(KEY_EXEC_ID) ?: return
            val callbacks = pendingRequests[execId] ?: return

            when (msg.what) {
                MSG_STREAM_OUTPUT -> {
                    val chunk = bundle.getString(KEY_OUTPUT_CHUNK) ?: ""
                    callbacks.onOutput(chunk)
                }
                MSG_EXECUTION_SUCCESS -> {
                    val result = bundle.getString(KEY_FINAL_RESULT) ?: ""
                    pendingRequests.remove(execId)
                    callbacks.onComplete(true, result)
                }
                MSG_EXECUTION_ERROR -> {
                    val error = bundle.getString(KEY_ERROR_MESSAGE) ?: "Python execution failure"
                    pendingRequests.remove(execId)
                    callbacks.onComplete(false, error)
                }
            }
        }
    })

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val messenger = android.os.Messenger(service)
            serviceMessenger = messenger
            isBound = true
            OmniLogger.log("NEXUS_IPC", "🤝 Bound to Nexus Python Bridge Service.")

            val pending = synchronized(readyQueue) {
                val copy = readyQueue.toList()
                readyQueue.clear()
                copy
            }
            pending.forEach { action ->
                try { action(messenger) } catch (_: Exception) {}
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            serviceMessenger = null
            isBound = false
            OmniLogger.log("NEXUS_IPC", "Disconnected from Nexus Python Service.")

            val orphaned = pendingRequests.toMap()
            pendingRequests.clear()
            orphaned.values.forEach { it.onComplete(false, "Nexus process disconnected unexpectedly.") }
        }
    }

    fun isAvailable(context: Context): Boolean {
        val pm = context.packageManager
        OmniLogger.log("NEXUS_DIAG", "🔍 Probing Nexus availability for target [$NEXUS_PACKAGE]...")

        // 1. Package Visibility Check
        val nexusInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(NEXUS_PACKAGE, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(NEXUS_PACKAGE, 0)
            }
        } catch (e: Exception) {
            OmniLogger.log("NEXUS_DIAG", "❌ getPackageInfo FAILED for '$NEXUS_PACKAGE': ${e.javaClass.simpleName} - ${e.message}")
            if (e is android.content.pm.PackageManager.NameNotFoundException) {
                OmniLogger.log("NEXUS_DIAG", "💡 REASON: Either Nexus is NOT installed, or Omni Hub base APK lacks <queries><package android:name=\"$NEXUS_PACKAGE\"/></queries> in its AndroidManifest!")
            }
            return false
        }

        OmniLogger.log("NEXUS_DIAG", "✅ Nexus Package FOUND! Version: ${nexusInfo.versionName} (Code: ${nexusInfo.versionCode})")

        // 2. Signature Check
        val sigResult = try {
            pm.checkSignatures(context.packageName, NEXUS_PACKAGE)
        } catch (e: Exception) {
            OmniLogger.log("NEXUS_DIAG", "❌ checkSignatures exception: ${e.message}")
            -99
        }

        val sigStatusText = when (sigResult) {
            android.content.pm.PackageManager.SIGNATURE_MATCH -> "SIGNATURE_MATCH (0) -> Perfect signature match!"
            android.content.pm.PackageManager.SIGNATURE_NO_MATCH -> "SIGNATURE_NO_MATCH (-3) -> DIFFERENT KEYSTORES USED!"
            android.content.pm.PackageManager.SIGNATURE_UNKNOWN_PACKAGE -> "SIGNATURE_UNKNOWN_PACKAGE (-4) -> Unknown package"
            android.content.pm.PackageManager.SIGNATURE_NEITHER_SIGNED -> "SIGNATURE_NEITHER_SIGNED (-1) -> Neither signed"
            android.content.pm.PackageManager.SIGNATURE_FIRST_NOT_SIGNED -> "SIGNATURE_FIRST_NOT_SIGNED (-2)"
            android.content.pm.PackageManager.SIGNATURE_SECOND_NOT_SIGNED -> "SIGNATURE_SECOND_NOT_SIGNED (-2)"
            else -> "UNKNOWN_STATUS_CODE ($sigResult)"
        }

        OmniLogger.log("NEXUS_DIAG", "🔐 checkSignatures result: $sigStatusText")

        // 3. Dump raw certificate hashes for visual comparison
        try {
            fun getSigHash(pkg: String): String {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pInfo = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    val sigs = pInfo.signingInfo?.apkContentsSigners ?: emptyArray()
                    sigs.joinToString { it.hashCode().toString(16) }
                } else {
                    @Suppress("DEPRECATION")
                    val pInfo = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    val sigs = pInfo.signatures ?: emptyArray()
                    sigs.joinToString { it.hashCode().toString(16) }
                }
            }
            val omniSig = getSigHash(context.packageName)
            val nexusSig = getSigHash(NEXUS_PACKAGE)
            OmniLogger.log("NEXUS_DIAG", "🔑 Omni Keystore Hash : [$omniSig]")
            OmniLogger.log("NEXUS_DIAG", "🔑 Nexus Keystore Hash: [$nexusSig]")
        } catch (e: Exception) {
            OmniLogger.log("NEXUS_DIAG", "⚠️ Could not extract certificate fingerprints: ${e.message}")
        }

        val isMatch = (sigResult == android.content.pm.PackageManager.SIGNATURE_MATCH)
        if (!isMatch) {
            OmniLogger.log("NEXUS_DIAG", "🚨 SECURITY GATE REJECTED: Certificates do not match. Re-sign Nexus and Omni with the exact same release.jks!")
        }
        return isMatch
    }

    fun execute(
        context: Context,
        code: String,
        onOutput: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        OmniLogger.log("NEXUS_IPC", "🚀 execute() requested for Python script (${code.length} chars)")
        if (!isAvailable(context)) {
            val diagMsg = "Nexus IPC check failed. Open Diagnostics Console (📋 button in Omni Hub) to view diagnostic logs."
            onComplete(false, diagMsg)
            return
        }

        val execId = "exec_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
        pendingRequests[execId] = RequestCallbacks(onOutput, onComplete)

        ensureBound(context) { messenger ->
            try {
                OmniLogger.log("NEXUS_IPC", "📤 Sending MSG_EXECUTE_CODE [ID: $execId] to Nexus Messenger...")
                val msg = android.os.Message.obtain(null, MSG_EXECUTE_CODE).apply {
                    replyTo = clientMessenger
                    data = android.os.Bundle().apply {
                        putString(KEY_CODE, code)
                        putString(KEY_EXEC_ID, execId)
                    }
                }
                messenger.send(msg)
            } catch (e: Exception) {
                pendingRequests.remove(execId)
                OmniLogger.log("NEXUS_IPC_ERR", "💥 Failed sending message to Nexus: ${e.message}")
                onComplete(false, "Failed dispatching execution to Nexus: ${e.message}")
            }
        }
    }

    private fun ensureBound(context: Context, onReady: (android.os.Messenger) -> Unit) {
        val current = serviceMessenger
        if (current != null && isBound) {
            onReady(current)
            return
        }

        readyQueue.add(onReady)

        val intent = Intent(BIND_ACTION).apply {
            setPackage(NEXUS_PACKAGE)
        }

        try {
            OmniLogger.log("NEXUS_IPC", "Connecting to Nexus via bindService ($BIND_ACTION)...")
            val bound = context.applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            OmniLogger.log("NEXUS_IPC", "bindService call returned: $bound")
            if (!bound) {
                readyQueue.clear()
                OmniLogger.log("NEXUS_IPC_ERR", "❌ bindService returned FALSE. Nexus is rejecting bind or service not declared!")
            }
        } catch (e: Exception) {
            readyQueue.clear()
            OmniLogger.log("NEXUS_IPC_ERR", "💥 Exception binding to Nexus: ${e.message}")
        }
    }
}

/**
 * Concrete implementation of the HostBridge instantiated by Omni Hub.
 */
class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    private val store = androidx.lifecycle.ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
}

class HostBridgeImpl(
    private val context: Context,
    private val pluginDir: File,
    private val onCloseRequested: () -> Unit
) : HostBridge {

    private var backPressedHandler: (() -> Boolean)? = null
    private val activeOverlays = java.util.concurrent.ConcurrentHashMap<String, Pair<View, OverlayLifecycleOwner?>>()
    private val overlayHandler = Handler(Looper.getMainLooper())

    override fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    override fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                OmniLogger.log("OVERLAY_ERR", "Failed launching overlay permission settings: ${e.message}")
            }
        }
    }

    override fun showOverlay(
        tag: String,
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        x: Int,
        y: Int,
        focusable: Boolean,
        touchable: Boolean,
        autoDismissMs: Long
    ): Boolean {
        if (!canDrawOverlays()) {
            OmniLogger.log("OVERLAY_WARN", "Cannot show overlay [$tag]: Overlay permission not granted.")
            return false
        }

        val action = Runnable {
            try {
                val appContext = context.applicationContext
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@Runnable

                // Synchronously clear previous view under this tag
                dismissOverlay(tag)

                // Attach Lifecycle and SavedState owners so Compose works inside WindowManager
                val lifecycleOwner = OverlayLifecycleOwner()
                view.setViewTreeLifecycleOwner(lifecycleOwner)
                view.setViewTreeViewModelStoreOwner(lifecycleOwner)
                view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

                if (!focusable) {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                if (!touchable) {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                }

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    width,
                    height,
                    windowType,
                    flags,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    this.gravity = gravity
                    this.x = x
                    this.y = y
                }

                wm.addView(view, params)
                activeOverlays[tag] = Pair(view, lifecycleOwner)
                OmniLogger.log("OVERLAY", "🚀 System overlay mounted: [$tag]")

                if (autoDismissMs > 0L) {
                    overlayHandler.postDelayed({
                        dismissOverlay(tag)
                    }, autoDismissMs)
                }
            } catch (e: Exception) {
                OmniLogger.log("OVERLAY_ERR", "Failed showing overlay [$tag]: ${e.message}")
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
        return true
    }

    override fun dismissOverlay(tag: String): Boolean {
        val entry = activeOverlays.remove(tag) ?: return false
        val (view, lifecycleOwner) = entry
        val action = Runnable {
            try {
                val appContext = context.applicationContext
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (view.isAttachedToWindow) {
                    wm?.removeView(view)
                }
                lifecycleOwner?.destroy()
                OmniLogger.log("OVERLAY", "🧹 System overlay dismissed: [$tag]")
            } catch (e: Exception) {
                OmniLogger.log("OVERLAY_ERR", "Error removing overlay [$tag]: ${e.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
        return true
    }

    override fun dismissAllOverlays() {
        val tags = activeOverlays.keys().toList()
        tags.forEach { dismissOverlay(it) }
    }

    override fun setOnBackPressedHandler(handler: (() -> Boolean)?) {
        backPressedHandler = handler
    }

    override fun handleBackPressed(): Boolean {
        return backPressedHandler?.invoke() ?: false
    }

    override fun showRecents() {
        RecentsDispatcher.showRecents()
    }

    override fun pickFiles(mimeType: String, allowMultiple: Boolean, onResult: (List<Uri>) -> Unit) {
        FilePickerDispatcher.pick(mimeType, allowMultiple, onResult)
    }

    override fun hasPermission(permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        if (hasPermission(permission)) {
            Handler(Looper.getMainLooper()).post { onResult(true) }
            return
        }
        PermissionDispatcher.request(arrayOf(permission)) { result ->
            onResult(result[permission] == true)
        }
    }

    override fun requestPermissions(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val missing = permissions.filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            Handler(Looper.getMainLooper()).post {
                onResult(permissions.associateWith { true })
            }
            return
        }
        PermissionDispatcher.request(permissions) { result ->
            val completeMap = permissions.associateWith { hasPermission(it) || result[it] == true }
            onResult(completeMap)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun close() {
        Handler(Looper.getMainLooper()).post {
            onCloseRequested()
        }
    }

    override fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun copyToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Omni Hub", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getClipboardText(): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    override fun vibrate(durationMs: Long) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    override fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun isCharging(): Boolean {
        val ifilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun getSystemInfo(): String {
        val json = JSONObject()
        json.put("model", Build.MODEL)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("brand", Build.BRAND)
        json.put("device", Build.DEVICE)
        json.put("android_version", Build.VERSION.RELEASE)
        json.put("sdk_int", Build.VERSION.SDK_INT)
        json.put("cpu_cores", Runtime.getRuntime().availableProcessors())
        json.put("arch", System.getProperty("os.arch"))

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        json.put("total_ram_mb", memInfo.totalMem / (1024 * 1024))
        json.put("available_ram_mb", memInfo.availMem / (1024 * 1024))

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        json.put("screen_resolution", "${dm.widthPixels}x${dm.heightPixels}")
        json.put("density_dpi", dm.densityDpi)

        return json.toString()
    }

    override fun getStorageStats(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val internalStat = android.os.StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = internalStat.blockCountLong * internalStat.blockSizeLong
        val freeBytes = internalStat.availableBlocksLong * internalStat.blockSizeLong
        
        map["total_internal"] = String.format("%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
        map["free_internal"] = String.format("%.2f GB", freeBytes / (1024.0 * 1024.0 * 1024.0))
        map["plugin_storage_path"] = pluginDir.absolutePath
        return map
    }

    override fun setScreenBrightness(percentage: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
            try {
                val value = ((percentage.coerceIn(0, 100) / 100f) * 255).toInt()
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (_: Exception) {}
        }
    }

    override fun setVolume(stream: String, level: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val targetStream = when (stream.uppercase()) {
                "MEDIA" -> AudioManager.STREAM_MUSIC
                "ALARM" -> AudioManager.STREAM_ALARM
                "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_RING
            }
            val max = am.getStreamMaxVolume(targetStream)
            val targetVol = ((level.coerceIn(0, 100) / 100f) * max).toInt()
            am.setStreamVolume(targetStream, targetVol, 0)
        } catch (_: Exception) {}
    }

    override fun getRingerMode(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.ringerMode
    }

    override fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun getWifiStatus(): String {
        return if (isNetworkAvailable()) "CONNECTED" else "DISCONNECTED"
    }

    override fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            response.body?.string()
        } catch (_: Exception) {
            null
        }
    }

    override fun httpPost(url: String, jsonBody: String): String? {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonBody.toRequestBody(mediaType)
            val request = Request.Builder().url(url).post(body).build()
            val response = httpClient.newCall(request).execute()
            response.body?.string()
        } catch (_: Exception) {
            null
        }
    }

    override fun sampleSensors(): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val json = JSONObject()
        val deferred = CompletableDeferred<Unit>()

        var lux = -1f
        var proximity = -1f
        var accel = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> lux = event.values[0]
                    Sensor.TYPE_PROXIMITY -> proximity = event.values[0]
                    Sensor.TYPE_ACCELEROMETER -> accel = event.values.clone()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        light?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        prox?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        acc?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

        Handler(Looper.getMainLooper()).postDelayed({
            sm.unregisterListener(listener)
            deferred.complete(Unit)
        }, 1000)

        runBlocking {
            withTimeoutOrNull(1500) { deferred.await() }
        }

        json.put("lux", lux)
        json.put("proximity", proximity)
        json.put("accel_x", accel.getOrElse(0) { 0f })
        json.put("accel_y", accel.getOrElse(1) { 0f })
        json.put("accel_z", accel.getOrElse(2) { 0f })
        return json.toString()
    }

    override fun getPluginDir(): String = pluginDir.absolutePath

    override fun saveFile(relativePath: String, content: ByteArray): String {
        val target = File(pluginDir, relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(content)
        return target.absolutePath
    }

    override fun readFile(relativePath: String): ByteArray? {
        val target = File(pluginDir, relativePath)
        return if (target.exists() && target.isFile) target.readBytes() else null
    }

    override fun listFiles(relativePath: String): List<String> {
        val target = File(pluginDir, relativePath)
        return target.listFiles()?.map { it.name } ?: emptyList()
    }

    override fun deleteFile(relativePath: String): Boolean {
        val target = File(pluginDir, relativePath)
        return target.deleteRecursively()
    }

    override fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    override fun runIntent(action: String, dataUri: String?, extras: Map<String, Any>?): Boolean {
        return try {
            val intent = if (!dataUri.isNullOrEmpty() && dataUri.startsWith("intent://")) {
                Intent.parseUri(dataUri, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(action).apply {
                    if (!dataUri.isNullOrEmpty()) {
                        data = Uri.parse(dataUri)
                    }
                    extras?.forEach { (k, v) ->
                        when (v) {
                            is Boolean -> putExtra(k, v)
                            is Int -> putExtra(k, v)
                            is Long -> putExtra(k, v)
                            is Float -> putExtra(k, v)
                            is Double -> putExtra(k, v)
                            else -> putExtra(k, v.toString())
                        }
                    }
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun executeShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun acquireWakeLock(tag: String) {
        try {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "omni:$tag").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if ((wifiLock == null || wifiLock?.isHeld == false) && wm != null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "omni:$tag").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    override fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
    }

    override fun startForegroundTask(title: String, message: String) {
        acquireWakeLock(title)
        if (context is android.app.Service) {
            OmniLogger.log("FOREGROUND", "Context is already an active host Service ($title). Bypassing nested FGS start.")
            return
        }
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.START_FOREGROUND"
                putExtra("extra_title", title)
                putExtra("extra_message", message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            OmniLogger.log("FOREGROUND_WARN", "Could not start OmniForegroundService: ${e.message}")
        }
    }

    override fun updateForegroundTask(message: String) {
        if (context is android.app.Service) {
            OmniLogger.log("FOREGROUND", "Task update: $message")
            return
        }
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.START_FOREGROUND"
                putExtra("extra_title", "Omni Hub Task")
                putExtra("extra_message", message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    override fun stopForegroundTask() {
        releaseWakeLock()
        if (context is android.app.Service) {
            return
        }
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.STOP_FOREGROUND"
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun startMediaPlayback(title: String, artist: String, isPlaying: Boolean, onAction: (Boolean) -> Unit) {
        acquireWakeLock("OmniMediaPlayback")
        MediaPlaybackDispatcher.registerListener(onAction)
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.START_MEDIA"
                putExtra("extra_title", title)
                putExtra("extra_message", artist)
                putExtra("extra_is_playing", isPlaying)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            OmniLogger.log("MEDIA_WARN", "Could not start media service: ${e.message}")
        }
    }

    override fun updateMediaPlayback(title: String, artist: String, isPlaying: Boolean) {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.UPDATE_MEDIA"
                putExtra("extra_title", title)
                putExtra("extra_message", artist)
                putExtra("extra_is_playing", isPlaying)
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun stopMediaPlayback() {
        releaseWakeLock()
        MediaPlaybackDispatcher.registerListener(null)
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.STOP_MEDIA"
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun requestMediaProjection(onResult: (Int, Intent?) -> Unit) {
        ScreenCaptureDispatcher.requestCapture(onResult)
    }

    override fun startProjectionService(
        resultCode: Int,
        data: Intent,
        title: String,
        message: String,
        onReady: (android.media.projection.MediaProjection?) -> Unit
    ) {
        acquireWakeLock("OmniProjection")
        MediaProjectionDispatcher.registerCallback(onReady)
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.START_PROJECTION"
                putExtra("extra_title", title)
                putExtra("extra_message", message)
                putExtra("extra_result_code", resultCode)
                putExtra("extra_result_data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            OmniLogger.log("PROJECTION_WARN", "Could not start projection service: ${e.message}")
            MediaProjectionDispatcher.dispatchProjectionReady(null)
        }
    }

    override fun stopProjectionService() {
        releaseWakeLock()
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.omni.hub.services.OmniForegroundService")
                action = "com.omni.hub.action.STOP_PROJECTION"
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun getMediaProjectionManager(): android.media.projection.MediaProjectionManager {
        return context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    override fun log(tag: String, message: String) {
        OmniLogger.log(tag, message)
    }

    override fun showDiagnostics() {
        DiagnosticsDispatcher.showDiagnostics()
    }

    override fun isPythonEngineAvailable(): Boolean {
        return NexusPythonClient.isAvailable(context)
    }

    override fun executePython(
        code: String,
        onOutput: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        NexusPythonClient.execute(context, code, onOutput, onComplete)
    }
}