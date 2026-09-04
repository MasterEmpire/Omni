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
import android.view.WindowManager
import android.widget.Toast
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
    fun showToast(message: String)
    fun copyToClipboard(text: String)
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

    // --- Logging ---
    fun log(tag: String, message: String)
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

/**
 * Concrete implementation of the HostBridge instantiated by Omni Hub.
 */
class HostBridgeImpl(
    private val context: Context,
    private val pluginDir: File,
    private val onCloseRequested: () -> Unit
) : HostBridge {

    private var backPressedHandler: (() -> Boolean)? = null

    override fun setOnBackPressedHandler(handler: (() -> Boolean)?) {
        backPressedHandler = handler
    }

    override fun handleBackPressed(): Boolean {
        return backPressedHandler?.invoke() ?: false
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
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "omni:$tag").apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiLock == null && wm != null) {
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
        com.omni.hub.services.OmniForegroundService.startMedia(context, title, artist, isPlaying, onAction)
    }

    override fun updateMediaPlayback(title: String, artist: String, isPlaying: Boolean) {
        com.omni.hub.services.OmniForegroundService.updateMedia(context, title, artist, isPlaying)
    }

    override fun stopMediaPlayback() {
        releaseWakeLock()
        com.omni.hub.services.OmniForegroundService.stopMedia(context)
    }

    override fun log(tag: String, message: String) {
        OmniLogger.log(tag, message)
    }
}