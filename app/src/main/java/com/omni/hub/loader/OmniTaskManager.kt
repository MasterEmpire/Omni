package com.omni.hub.loader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.omni.hub.MainActivity
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.OmniLogger
import com.omni.hub.container.PluginContainerActivity
import com.omni.hub.container.PluginSlot0Activity
import com.omni.hub.container.PluginSlot1Activity
import com.omni.hub.container.PluginSlot2Activity
import com.omni.hub.container.PluginSlot3Activity
import com.omni.hub.container.PluginSlot4Activity
import com.omni.hub.container.PluginSlot5Activity
import com.omni.hub.container.PluginSlot6Activity
import com.omni.hub.container.PluginSlot7Activity
import com.omni.hub.container.PluginSlot8Activity
import com.omni.hub.container.PluginSlot9Activity
import com.omni.hub.container.PluginSlot10Activity
import com.omni.hub.container.PluginSlot11Activity
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class AppTaskSession(
    val taskId: String,
    val pluginId: String,
    val pluginName: String,
    val entryClass: String,
    val pluginView: View,
    val bridge: HostBridgeImpl,
    val loadedPlugin: LoadedPlugin,
    val isStandaloneWindow: Boolean = false,
    var activityRef: WeakReference<PluginContainerActivity>? = null,
    var thumbnail: Bitmap? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val isCleanedUp: AtomicBoolean = AtomicBoolean(false)
)

object OmniTaskManager {
    private const val PREFS_WINDOW_MODE = "omni_window_mode_prefs"
    private const val KEY_STANDALONE_PREFIX = "standalone_"

    val activeSessions = mutableStateListOf<AppTaskSession>()
    var currentForegroundSession by mutableStateOf<AppTaskSession?>(null)
    var isRecentsModalOpen by mutableStateOf(false)
    val sessionHistory = mutableListOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingStandaloneLaunchMs = ConcurrentHashMap<String, Long>()
    private val pluginSlotMap = ConcurrentHashMap<String, Int>()
    private val SLOT_CLASSES: List<Class<out PluginContainerActivity>> = listOf(
        PluginSlot0Activity::class.java,
        PluginSlot1Activity::class.java,
        PluginSlot2Activity::class.java,
        PluginSlot3Activity::class.java,
        PluginSlot4Activity::class.java,
        PluginSlot5Activity::class.java,
        PluginSlot6Activity::class.java,
        PluginSlot7Activity::class.java,
        PluginSlot8Activity::class.java,
        PluginSlot9Activity::class.java,
        PluginSlot10Activity::class.java,
        PluginSlot11Activity::class.java
    )

    private fun resolveSlotClass(context: Context, pluginId: String): Class<out PluginContainerActivity> {
        val activeSlotOwners = activeSessions
            .filter { it.isStandaloneWindow && it.pluginId != pluginId }
            .mapNotNull { pluginSlotMap[it.pluginId] }
            .toSet()

        val existingSlot = pluginSlotMap[pluginId]
        if (existingSlot != null && !activeSlotOwners.contains(existingSlot) && existingSlot in SLOT_CLASSES.indices) {
            return SLOT_CLASSES[existingSlot]
        }

        val freeSlot = SLOT_CLASSES.indices.firstOrNull { !activeSlotOwners.contains(it) }
        if (freeSlot != null) {
            pluginSlotMap[pluginId] = freeSlot
            return SLOT_CLASSES[freeSlot]
        }

        val lruStandalone = activeSessions.lastOrNull { it.isStandaloneWindow && it.pluginId != pluginId }
        if (lruStandalone != null) {
            val recycledSlot = pluginSlotMap[lruStandalone.pluginId] ?: 0
            killTask(context, lruStandalone.taskId)
            pluginSlotMap[pluginId] = recycledSlot
            return SLOT_CLASSES[recycledSlot]
        }

        pluginSlotMap[pluginId] = 0
        return SLOT_CLASSES[0]
    }

    fun isStandaloneWindowEnabled(context: Context, pluginId: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_WINDOW_MODE, Context.MODE_PRIVATE)
        return prefs.getBoolean("$KEY_STANDALONE_PREFIX$pluginId", true)
    }

    fun setStandaloneWindowEnabled(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String,
        enabled: Boolean
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_WINDOW_MODE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$KEY_STANDALONE_PREFIX$pluginId", enabled).apply()
        handleWindowModeTransition(context, pluginId, pluginName, entryClass, enabled)
    }

    fun pushHistory(taskId: String) {
        sessionHistory.removeAll { it == taskId }
        sessionHistory.add(taskId)
    }

    fun openRecents() {
        currentForegroundSession?.let { captureSnapshot(it) }
        isRecentsModalOpen = true
    }

    fun closeRecents() {
        isRecentsModalOpen = false
    }

    fun launchOrResume(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String
    ): AppTaskSession? {
        val useStandalone = isStandaloneWindowEnabled(context, pluginId)
        val existing = activeSessions.find { it.pluginId == pluginId }

        if (existing != null) {
            val dexFile = File(context.getDir("plugins", Context.MODE_PRIVATE), "$pluginId/classes.dex")
            val isBytecodeNewer = dexFile.exists() && dexFile.lastModified() > existing.startedAt

            if (isBytecodeNewer) {
                OmniLogger.log(
                    "TASK_MANAGER",
                    "🔄 [HOT-SWAP] Newer bytecode on disk for [$pluginName] (DEX: ${dexFile.lastModified()} > Session: ${existing.startedAt}). Decapitating stale session!"
                )
                PluginLoader.invalidate(pluginId)
                killTask(context, existing.taskId)
            } else if (useStandalone) {
                if (!existing.isStandaloneWindow) {
                    OmniLogger.log("TASK_MANAGER", "Migrating [$pluginName] from embedded host to standalone window.")
                    teardownSessionForMigration(context, existing)
                } else {
                    val act = existing.activityRef?.get()
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        OmniLogger.log("TASK_MANAGER", "Resuming existing standalone window for [$pluginName]")
                        startStandaloneActivity(context, pluginId, pluginName, entryClass)
                        return existing
                    } else {
                        OmniLogger.log("TASK_MANAGER", "Existing standalone window for [$pluginName] is dead. Purging.")
                        killTask(context, existing.taskId)
                    }
                }
            } else {
                if (existing.isStandaloneWindow) {
                    OmniLogger.log("TASK_MANAGER", "Migrating [$pluginName] from standalone window into host.")
                    teardownSessionForMigration(context, existing)
                } else {
                    val act = existing.pluginView.context as? Activity
                    if (act != null && (act.isDestroyed || act.isFinishing)) {
                        OmniLogger.log("TASK_MANAGER", "Existing embedded session for [$pluginName] has dead activity context. Purging.")
                        killTask(context, existing.taskId)
                    } else {
                        OmniLogger.log("TASK_MANAGER", "Resuming existing embedded session for [$pluginName]")
                        if (currentForegroundSession != null && currentForegroundSession?.taskId != existing.taskId) {
                            pushHistory(currentForegroundSession!!.taskId)
                        }
                        activeSessions.remove(existing)
                        activeSessions.add(0, existing)
                        currentForegroundSession = existing
                        return existing
                    }
                }
            }
        }

        if (useStandalone) {
            val now = System.currentTimeMillis()
            val lastAttempt = pendingStandaloneLaunchMs[pluginId] ?: 0L
            if (now - lastAttempt < 850L) {
                OmniLogger.log("TASK_MANAGER", "Debounced duplicate standalone launch for [$pluginName]")
                return null
            }
            pendingStandaloneLaunchMs[pluginId] = now
            OmniLogger.log("TASK_MANAGER", "Spawning standalone OS window for [$pluginName] ($entryClass)")
            startStandaloneActivity(context, pluginId, pluginName, entryClass)
            return null
        }

        OmniLogger.log("TASK_MANAGER", "Instantiating embedded session for [$pluginName] ($entryClass)")
        val loaded = PluginLoader.loadFromDir(context, pluginId, entryClass)

        val bridge = HostBridgeImpl(
            context = context,
            pluginDir = loaded.dataDir,
            pluginId = loaded.id,
            onWindowModeChanged = { enabled ->
                handleWindowModeTransition(context.applicationContext, loaded.id, loaded.name, loaded.entryClass, enabled)
            }
        ) {
            suspendCurrent()
        }

        val pluginView = loaded.instance.onCreateView(context, bridge, loaded.baseDir.absolutePath)
        (pluginView as? AbstractComposeView)?.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        val session = AppTaskSession(
            taskId = "task_${System.currentTimeMillis()}_${loaded.id}",
            pluginId = loaded.id,
            pluginName = loaded.name,
            entryClass = loaded.entryClass,
            pluginView = pluginView,
            bridge = bridge,
            loadedPlugin = loaded,
            isStandaloneWindow = false
        )

        if (currentForegroundSession != null) {
            pushHistory(currentForegroundSession!!.taskId)
        }
        activeSessions.add(0, session)
        currentForegroundSession = session
        return session
    }

    private fun startStandaloneActivity(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String
    ) {
        val slotClass = resolveSlotClass(context, pluginId)
        val intent = Intent(context, slotClass).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("omni://plugin/$pluginId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PluginContainerActivity.EXTRA_PLUGIN_ID, pluginId)
            putExtra(PluginContainerActivity.EXTRA_PLUGIN_NAME, pluginName)
            putExtra(PluginContainerActivity.EXTRA_ENTRY_CLASS, entryClass)
        }
        context.startActivity(intent)
    }

    fun registerStandaloneSession(
        activity: PluginContainerActivity,
        loaded: LoadedPlugin,
        pluginView: View,
        bridge: HostBridgeImpl
    ): AppTaskSession {
        pendingStandaloneLaunchMs.remove(loaded.id)

        // Single source of truth enforcement: purge any prior session for this pluginId
        val duplicates = activeSessions.filter { it.pluginId == loaded.id }
        duplicates.forEach { stale ->
            sessionHistory.removeAll { it == stale.taskId }
            if (currentForegroundSession?.taskId == stale.taskId) {
                currentForegroundSession = null
            }
            if (stale.isStandaloneWindow) {
                val oldAct = stale.activityRef?.get()
                if (oldAct != null && oldAct !== activity && !oldAct.isFinishing && !oldAct.isDestroyed) {
                    stale.isCleanedUp.set(true)
                    oldAct.finishAndRemoveTask()
                }
            } else {
                (stale.pluginView.parent as? ViewGroup)?.removeView(stale.pluginView)
                stale.isCleanedUp.set(true)
            }
            activeSessions.remove(stale)
        }

        val session = AppTaskSession(
            taskId = "task_${System.currentTimeMillis()}_${loaded.id}",
            pluginId = loaded.id,
            pluginName = loaded.name,
            entryClass = loaded.entryClass,
            pluginView = pluginView,
            bridge = bridge,
            loadedPlugin = loaded,
            isStandaloneWindow = true,
            activityRef = WeakReference(activity)
        )
        activeSessions.add(0, session)
        OmniLogger.log("TASK_MANAGER", "Registered single standalone session for [${loaded.name}] (${loaded.id})")
        return session
    }

    fun onStandaloneActivityResumed(pluginId: String, activity: PluginContainerActivity) {
        val session = activeSessions.find { it.pluginId == pluginId && it.activityRef?.get() === activity } ?: return
        val idx = activeSessions.indexOf(session)
        if (idx > 0) {
            activeSessions.removeAt(idx)
            activeSessions.add(0, session)
        }
    }

    fun onStandaloneActivityPaused(pluginId: String, activity: PluginContainerActivity) {
        val session = activeSessions.find { it.pluginId == pluginId && it.activityRef?.get() === activity } ?: return
        captureSnapshot(session)
    }

    fun onStandaloneActivityDestroyed(pluginId: String, activity: PluginContainerActivity) {
        val session = activeSessions.find { it.pluginId == pluginId && it.activityRef?.get() === activity } ?: return
        OmniLogger.log("TASK_MANAGER", "Standalone window destroyed for [${session.pluginName}]")
        sessionHistory.removeAll { it == session.taskId }
        pluginSlotMap.remove(pluginId)
        cleanupSessionLifecycle(activity.applicationContext, session)
        activeSessions.remove(session)
    }

    fun closeStandaloneWindow(activity: PluginContainerActivity, pluginId: String) {
        val session = activeSessions.find { it.pluginId == pluginId && it.activityRef?.get() === activity }
        if (session != null) {
            killTask(activity, session.taskId)
        } else if (!activity.isFinishing && !activity.isDestroyed) {
            activity.finishAndRemoveTask()
        }
    }

    fun handleWindowModeTransition(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String,
        enabled: Boolean
    ) {
        mainHandler.post {
            val existing = activeSessions.find { it.pluginId == pluginId } ?: return@post
            if (existing.isStandaloneWindow == enabled) return@post

            OmniLogger.log("TASK_MANAGER", "Transitioning [$pluginName] window mode -> standalone=$enabled")
            teardownSessionForMigration(context, existing)

            if (enabled) {
                launchOrResume(context, pluginId, pluginName, entryClass)
            } else {
                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra("extra_open_plugin_id", pluginId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun teardownSessionForMigration(context: Context, session: AppTaskSession) {
        sessionHistory.removeAll { it == session.taskId }
        pluginSlotMap.remove(session.pluginId)
        val wasForeground = currentForegroundSession?.taskId == session.taskId
        if (wasForeground) {
            (session.pluginView.parent as? ViewGroup)?.removeView(session.pluginView)
            currentForegroundSession = null
        }

        cleanupSessionLifecycle(context, session)
        activeSessions.remove(session)

        if (session.isStandaloneWindow) {
            session.activityRef?.get()?.let { act ->
                if (!act.isFinishing && !act.isDestroyed) {
                    act.finishAndRemoveTask()
                }
            }
        }
    }

    private fun cleanupSessionLifecycle(context: Context, session: AppTaskSession) {
        if (session.isCleanedUp.getAndSet(true)) return

        val isDaemonActive = PluginTaskEngine.isDaemonEnabled(context, session.pluginId) ||
            session.pluginId == "scroll_lock" ||
            session.pluginId == "focus_shield"

        if (!isDaemonActive) {
            try {
                session.bridge.dismissAllOverlays()
                session.loadedPlugin.instance.onStop(context.applicationContext)
            } catch (e: Exception) {
                OmniLogger.log("TASK_MANAGER_ERR", "Error onStop for [${session.pluginName}]: ${e.message}")
            }
        } else {
            OmniLogger.log("TASK_MANAGER", "🛡️ ${session.pluginName} UI closed: background daemon stays running.")
        }
    }

    fun captureSnapshot(session: AppTaskSession) {
        val v = session.pluginView
        if (v.width > 0 && v.height > 0) {
            try {
                val scale = 0.35f
                val w = (v.width * scale).toInt().coerceAtLeast(1)
                val h = (v.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                v.draw(canvas)
                session.thumbnail = bitmap
            } catch (_: Exception) {}
        }
    }

    fun suspendCurrent(returnToDashboard: Boolean = false) {
        val current = currentForegroundSession ?: return
        OmniLogger.log("TASK_MANAGER", "Suspending [${current.pluginName}]")
        captureSnapshot(current)
        (current.pluginView.parent as? ViewGroup)?.removeView(current.pluginView)

        if (returnToDashboard) {
            sessionHistory.clear()
            currentForegroundSession = null
            return
        }

        var nextSession: AppTaskSession? = null
        while (sessionHistory.isNotEmpty()) {
            val prevTaskId = sessionHistory.removeAt(sessionHistory.lastIndex)
            val found = activeSessions.find { it.taskId == prevTaskId && !it.isStandaloneWindow }
            if (found != null && found.taskId != current.taskId) {
                nextSession = found
                break
            }
        }

        if (nextSession != null) {
            OmniLogger.log("TASK_MANAGER", "Navigating back to previous session [${nextSession.pluginName}]")
            currentForegroundSession = nextSession
        } else {
            OmniLogger.log("TASK_MANAGER", "No previous embedded session in stack. Returning to host dashboard.")
            currentForegroundSession = null
        }
    }

    fun resumeSession(context: Context, session: AppTaskSession) {
        if (session.isStandaloneWindow) {
            val act = session.activityRef?.get()
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                OmniLogger.log("TASK_MANAGER", "Focusing standalone window [${session.pluginName}] from Recents")
                startStandaloneActivity(context, session.pluginId, session.pluginName, session.entryClass)
            } else {
                OmniLogger.log("TASK_MANAGER", "Recreating stale standalone window [${session.pluginName}] from Recents")
                killTask(context, session.taskId)
                launchOrResume(context, session.pluginId, session.pluginName, session.entryClass)
            }
            return
        }

        OmniLogger.log("TASK_MANAGER", "Resuming [${session.pluginName}] from Recents")
        if (currentForegroundSession != null && currentForegroundSession?.taskId != session.taskId) {
            pushHistory(currentForegroundSession!!.taskId)
        }
        activeSessions.remove(session)
        activeSessions.add(0, session)
        currentForegroundSession = session
    }

    fun killTask(context: Context, taskId: String) {
        val target = activeSessions.find { it.taskId == taskId } ?: return
        OmniLogger.log("TASK_MANAGER", "Killing task [${target.pluginName}] (standalone=${target.isStandaloneWindow})")
        sessionHistory.removeAll { it == taskId }

        cleanupSessionLifecycle(context, target)

        val isTargetForeground = currentForegroundSession?.taskId == taskId
        pluginSlotMap.remove(target.pluginId)
        activeSessions.remove(target)

        if (target.isStandaloneWindow) {
            target.activityRef?.get()?.let { act ->
                if (!act.isFinishing && !act.isDestroyed) {
                    act.finishAndRemoveTask()
                }
            }
        } else if (isTargetForeground) {
            suspendCurrent()
        }
    }

    fun killAllTasks(context: Context) {
        OmniLogger.log("TASK_MANAGER", "Clearing all ${activeSessions.size} active sessions")
        sessionHistory.clear()
        val snapshot = activeSessions.toList()
        snapshot.forEach { session ->
            cleanupSessionLifecycle(context, session)
            if (session.isStandaloneWindow) {
                session.activityRef?.get()?.let { act ->
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.finishAndRemoveTask()
                    }
                }
            } else {
                (session.pluginView.parent as? ViewGroup)?.removeView(session.pluginView)
            }
        }
        pluginSlotMap.clear()
        currentForegroundSession = null
        activeSessions.clear()
    }

    fun reloadPluginSession(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String,
        reopenForeground: Boolean = true
    ): AppTaskSession? {
        OmniLogger.log("TASK_MANAGER", "Hot-reloading mini app session for [$pluginName] ($pluginId)")
        val existing = activeSessions.find { it.pluginId == pluginId }
        val wasForeground = currentForegroundSession?.pluginId == pluginId

        PluginLoader.invalidate(pluginId)
        if (existing != null) {
            killTask(context, existing.taskId)
        }

        val useStandalone = isStandaloneWindowEnabled(context, pluginId)
        if (useStandalone && !reopenForeground && !wasForeground) {
            return null
        }

        val newSession = launchOrResume(context, pluginId, pluginName, entryClass)
        if (!useStandalone && !wasForeground && !reopenForeground) {
            suspendCurrent(returnToDashboard = true)
        }
        return newSession
    }
}