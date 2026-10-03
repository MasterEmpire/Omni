package com.omni.plugin.spotify

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.os.SystemClock
import kotlinx.coroutines.*
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.media.projection.MediaProjection
import java.io.FileInputStream
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

enum class EngineState(val label: String, val color: Color) {
    DISARMED("RADAR MONITORING (RECORDING DISARMED)", Color(0xFF8B949E)),
    ARMED_LISTENING("RADAR ARMED (READY TO CAPTURE 0:00)", Color(0xFF1DB954)),
    RECORDING("CAPTURING CLEAN STREAM", Color(0xFF58A6FF)),
    MANUAL_RECORDING("MANUAL AUDIO CAPTURE ACTIVE", Color(0xFFFF7B72)),
    SKIPPING_AD("AD SHIELD ACTIVE: SKIPPING COMMERCIAL", Color(0xFFD29922)),
    WAITING_CLEAN_START("JOINED MID-TRACK (WAITING FOR NEXT 0:00)", Color(0xFFBC8CFF)),
    ALREADY_EXISTS("SONG ALREADY IN VAULT", Color(0xFF388BFD)),
    INTERRUPTED_DISCARDED("INTERRUPTED (DISCARDED)", Color(0xFFF85149))
}

data class VaultTrack(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val durationMs: Long = 0L,
    val coverBytes: ByteArray? = null
)

data class ActiveRecordingTake(
    val trackId: String,
    val trackTitle: String,
    val artistName: String,
    val expectedDurationMs: Long,
    val tempFile: File,
    val outputStream: FileOutputStream,
    val startedAtMs: Long = SystemClock.elapsedRealtime()
)

data class TranscodeJob(
    val tempPcmFile: File,
    val vaultDir: File,
    val artist: String,
    val title: String,
    val trackId: String,
    val recordedDurationMs: Long,
    val targetLengthMs: Long
)

data class DiscardInfo(
    val title: String,
    val reason: String,
    val timestamp: Long = SystemClock.elapsedRealtime()
)

data class SavingInfo(
    val title: String,
    val artist: String,
    val isTranscoding: Boolean = true
)

fun getVaultDirectory(context: Context): File {
    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    val publicVault = File(musicDir, "Omni Spotify")
    if (!publicVault.exists()) publicVault.mkdirs()
    if (publicVault.canWrite()) return publicVault

    // Scoped Storage fallback: Guaranteed accessible on Android 10+
    val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
    val appVault = File(appMusic ?: context.filesDir, "Omni Spotify").apply { if (!exists()) mkdirs() }
    return appVault
}

fun getStagingDirectory(context: Context): File {
    val stagingDir = File(context.cacheDir, "spotify_staging")
    if (!stagingDir.exists()) stagingDir.mkdirs()
    return stagingDir
}

fun sanitizeFilename(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}

class SpotifyRecorderPlugin : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        activeContext = context
        activeBridge = bridge
        activePluginInstance = this
        ensureReceiverRegistered(context)

        // Ghost State Sentinel: If MediaProjection token is missing, force-purge orphaned FGS streaming icons
        if (mediaProjection == null) {
            isArmed = false
            isRecording = false
            isManualRecording = false
            engineState = EngineState.DISARMED
            bridge.stopProjectionService()
            bridge.releaseWakeLock()
            setDaemonState(context, false)
        } else if (isArmed) {
            bridge.acquireWakeLock("SpotifyRecorderSentinel")
            setDaemonState(context, true)
        }

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0D1117),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFF1DB954)
                    )
                ) {
                    SpotifyVaultScreen(context, bridge)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        activeContext = context
        activeBridge = bridge
        activePluginInstance = this
        ensureReceiverRegistered(context)
        if (isArmed) {
            bridge.acquireWakeLock("SpotifyRecorderSentinel")
        }
        bridge.log("SPOTIFY_RECORDER", "🛡️ Spotify Recorder daemon armed. Background sentinel active.")
    }

    override fun onStop(context: Context) {
        if (activePluginInstance == this) {
            activePluginInstance = null
        }
        stateUpdater = null
        manualRecStateUpdater = null
        trackMetaUpdater = null
        statsUpdater = null
        vaultRefreshTrigger = null
        discardUpdater = null
        savingUpdater = null
        activeBridge?.log("SPOTIFY_RECORDER", "🛡️ Spotify Recorder UI detached. Background audio capture & radar sentinel remain 100% active!")
    }

    private val spotifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val extrasSummary = intent.extras?.let { bundle ->
                bundle.keySet().joinToString(" | ") { key ->
                    val value = bundle.get(key)
                    val type = value?.javaClass?.simpleName ?: "null"
                    "$key ($type)=$value"
                }
            } ?: "no extras"
            activeBridge?.log("SPOTIFY_BROADCAST", "📥 RX [$action] -> $extrasSummary")

            when (action) {
                "com.spotify.music.metadatachanged",
                "com.spotify.mobile.android.metadatachanged" -> handleMetadataChanged(intent)
                "com.spotify.music.playbackstatechanged",
                "com.spotify.mobile.android.playbackstatechanged" -> handlePlaybackStateChanged(intent)
                else -> activeBridge?.log("SPOTIFY_BROADCAST", "ℹ️ Unhandled broadcast action: $action")
            }
        }
    }

    private fun handleMetadataChanged(intent: Intent) {
        if (isManualRecording) {
            activeBridge?.log("SPOTIFY_RADAR", "ℹ️ Ignored Spotify track metadata: Universal manual recording is active.")
            return
        }
        val newTrackId = intent.getStringExtra("id") ?: ""
        val newTrack = intent.getStringExtra("track")
            ?: intent.getStringExtra("title")
            ?: "Unknown Track"
        val newArtist = intent.getStringExtra("artist") ?: "Unknown Artist"

        val rawLength = intent.getIntExtra("length", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("length", 0L)
        val newLengthMs = if (rawLength in 1..10000) rawLength * 1000L else rawLength
        val newPos = intent.getIntExtra("playbackPosition", 0).toLong()
        val isPlaying = intent.getBooleanExtra("playing", true)

        activeBridge?.log(
            "SPOTIFY_METADATA",
            "📥 [METADATA_IN] Title='$newTrack' | Artist='$newArtist' | ID='$newTrackId' | Pos=${newPos}ms | Len=${newLengthMs}ms | Playing=$isPlaying"
        )

        pauseDebounceJob?.cancel()
        pauseDebounceJob = null

        // 1. Ignore blank / intermediate transitional Spotify broadcast glitches
        if (newTrackId.isEmpty() && (newTrack.isEmpty() || newTrack == "Unknown Track")) {
            activeBridge?.log("SPOTIFY_RADAR", "ℹ️ Ignored blank transitional intent from Spotify.")
            return
        }

        // 2. If currently recording previous take, finalize & commit cleanly before moving on
        if (isRecording) {
            val previousTakeTitle = activeTake?.trackTitle ?: currentTrackTitle
            activeBridge?.log("SPOTIFY_RADAR", "🔄 Track advance detected while recording. Finalizing active take: '$previousTakeTitle'")
            finalizeCurrentRecording(reason = "Advanced to new track '$newTrack'")
        }

        // 3. Precision Ad Detection (Immune to Spotify Singles, empty transient IDs, and false flags)
        val isExplicitTrackUri = newTrackId.contains(":track:")
        val isExplicitAdUri = newTrackId.contains(":ad:")
        val isAdTitleOrArtist = newTrack.equals("Advertisement", ignoreCase = true) ||
            newTrack.startsWith("Spotify - ", ignoreCase = true) ||
            (newArtist.equals("Spotify", ignoreCase = true) && !isExplicitTrackUri)

        val isIdentifiedAd = isExplicitAdUri || (!isExplicitTrackUri && isAdTitleOrArtist)

        activeBridge?.log(
            "SPOTIFY_AD_EVAL",
            "🛡️ Ad Evaluation -> isExplicitTrackUri=$isExplicitTrackUri, isExplicitAdUri=$isExplicitAdUri, isAdTitleOrArtist=$isAdTitleOrArtist => isIdentifiedAd=$isIdentifiedAd"
        )

        if (isIdentifiedAd) {
            isAdActive = true
            adTitle = if (newTrack.isNotEmpty() && !newTrack.equals("Unknown Track", true)) newTrack else "Commercial Advertisement"
            adArtist = if (newArtist.isNotEmpty() && !newArtist.equals("Unknown Artist", true)) newArtist else "Spotify Commercial Stream"
            currentLengthMs = newLengthMs
            currentPositionMs = newPos
            isPlayingTrack = isPlaying
            lastSyncTimestamp = SystemClock.elapsedRealtime()

            countAds++
            statsUpdater?.invoke(countSaved, countDiscarded, countAds)
            stateUpdater?.invoke(EngineState.SKIPPING_AD)
            trackMetaUpdater?.invoke(adTitle, adArtist, newLengthMs, newPos, isPlaying)

            activeBridge?.log("SPOTIFY_RADAR", "🛡️ Shield engaged for: '$adTitle' by '$adArtist' ($newTrackId). Audio recording suppressed.")
            return
        }

        // Legitimate song track confirmed -> reset ad state completely
        isAdActive = false
        adTitle = ""
        adArtist = ""
        currentTrackId = newTrackId
        currentTrackTitle = newTrack
        currentArtist = newArtist
        currentLengthMs = newLengthMs
        currentPositionMs = newPos
        isPlayingTrack = isPlaying
        lastSyncTimestamp = SystemClock.elapsedRealtime()
        trackMetaUpdater?.invoke(newTrack, newArtist, newLengthMs, newPos, isPlaying)

        // 4. Duplicate Vault Check (.m4a and .wav)
        val ctx = activeContext ?: return
        val vaultDir = getVaultDirectory(ctx)
        val baseName = "${sanitizeFilename(newArtist)} - ${sanitizeFilename(newTrack)}"
        val m4aFile = File(vaultDir, "$baseName.m4a")
        val wavFile = File(vaultDir, "$baseName.wav")
        if ((m4aFile.exists() && m4aFile.length() > 1000) || (wavFile.exists() && wavFile.length() > 44)) {
            stateUpdater?.invoke(EngineState.ALREADY_EXISTS)
            activeBridge?.log("SPOTIFY_RADAR", "Track already in vault: $baseName. Audio recording skipped.")
            return
        }

        // 5. Clean Start Policy (Allow up to 2500ms to tolerate Android intent propagation latency)
        if (newPos > 2500L) {
            stateUpdater?.invoke(EngineState.WAITING_CLEAN_START)
            activeBridge?.log("SPOTIFY_RADAR", "Mid-track start (${newPos}ms > 2500ms). Waiting for next clean 0:00 track.")
            return
        }

        // 6. Conditions met: Launch Audio Stream Capture if armed
        if (isArmed && isPlaying && mediaProjection != null) {
            val stagingDir = getStagingDirectory(ctx)
            startAudioRecording(stagingDir, newTrackId, newTrack, newArtist, newLengthMs)
        } else if (!isArmed) {
            stateUpdater?.invoke(EngineState.DISARMED)
            activeBridge?.log("SPOTIFY_RADAR", "Radar disarmed. Track detected but not recording: '$newTrack'")
        }
    }

    private fun handlePlaybackStateChanged(intent: Intent) {
        val isPlaying = intent.getBooleanExtra("playing", false)
        val pos = intent.getIntExtra("playbackPosition", -1).toLong()

        isPlayingTrack = isPlaying
        lastSyncTimestamp = SystemClock.elapsedRealtime()

        if (pos >= 0) {
            currentPositionMs = pos
        }

        activeBridge?.log(
            "SPOTIFY_PLAYBACK",
            "📻 [PLAYBACK_STATE] playing=$isPlaying, pos=${currentPositionMs}ms, isRecording=$isRecording, isAdActive=$isAdActive"
        )

        if (!isAdActive) {
            trackMetaUpdater?.invoke(currentTrackTitle, currentArtist, currentLengthMs, currentPositionMs, isPlaying)
        }

        if (isManualRecording) {
            return
        }

        if (isRecording) {
            val take = activeTake
            val recordedMs = (recordedBytesCount * 1000L) / (44100 * 2 * 2)
            val targetLen = take?.expectedDurationMs ?: currentLengthMs

            if (!isPlaying) {
                if (targetLen > 0 && recordedMs >= targetLen - 4000L) {
                    activeBridge?.log("SPOTIFY_RADAR", "Track paused near completion (${recordedMs}ms/${targetLen}ms). Finalizing take.")
                    finalizeCurrentRecording(reason = "Paused near natural completion")
                } else {
                    activeBridge?.log("SPOTIFY_RADAR", "Playback paused (${recordedMs}ms of ${targetLen}ms). Armed 6s grace window before discarding...")
                    pauseDebounceJob?.cancel()
                    pauseDebounceJob = transcodeScope.launch {
                        delay(6000L)
                        if (!isPlayingTrack && isRecording) {
                            activeBridge?.log("SPOTIFY_RADAR", "Grace period expired without playback resume. Discarding take.")
                            abortAndDiscard("Playback remained paused past grace window")
                        }
                    }
                }
            } else {
                if (pauseDebounceJob?.isActive == true) {
                    activeBridge?.log("SPOTIFY_RADAR", "Playback resumed within grace period. Cancelled discard timer.")
                    pauseDebounceJob?.cancel()
                    pauseDebounceJob = null
                }
                if (pos >= 0 && targetLen > 0) {
                    if (pos <= 2000L && recordedMs >= targetLen - 4000L) {
                        activeBridge?.log("SPOTIFY_RADAR", "Playhead reset to 0:00 after full play (${recordedMs}ms). Finalizing take.")
                        finalizeCurrentRecording(reason = "Playhead reset to 0:00 after full play")
                    }
                }
            }
        }
    }

    private fun startAudioRecording(
        stagingDir: File,
        trackId: String,
        trackTitle: String,
        artistName: String,
        expectedDurationMs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) {
            activeBridge?.log("SPOTIFY_REC_ERR", "Cannot start recording: Android Q or MediaProjection missing.")
            return
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            val tempFile = File(stagingDir, ".recording_${System.currentTimeMillis()}.tmp")
            val fos = FileOutputStream(tempFile)
            fos.write(ByteArray(44)) // 44-byte dummy WAV header placeholder

            val take = ActiveRecordingTake(
                trackId = trackId,
                trackTitle = trackTitle,
                artistName = artistName,
                expectedDurationMs = expectedDurationMs,
                tempFile = tempFile,
                outputStream = fos
            )

            synchronized(recordLock) {
                audioRecord = record
                activeTake = take
                recordedBytesCount = 0L
                wasInterrupted = false
                isRecording = true
                lastDiscardInfo = null
            }
            discardUpdater?.invoke(null)

            record.startRecording()
            stateUpdater?.invoke(EngineState.RECORDING)
            activeBridge?.log(
                "SPOTIFY_RECORDER",
                "🎙️ [RECORD_START] Frozen Take Identity: Title='$trackTitle' | Artist='$artistName' | ID='$trackId' | ExpectedLen=${expectedDurationMs}ms | Temp=${tempFile.name}"
            )

            recordThread = Thread {
                val buffer = ByteArray(minBuf)
                var consecutiveErrors = 0
                var normalExit = false

                try {
                    while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            consecutiveErrors = 0
                            fos.write(buffer, 0, read)
                            recordedBytesCount += read

                            val recMs = (recordedBytesCount * 1000L) / (44100 * 2 * 2)
                            if (expectedDurationMs > 0 && recMs >= expectedDurationMs - 200L) {
                                activeBridge?.log(
                                    "SPOTIFY_RECORDER",
                                    "🎯 [AUTO-COMMIT] Track '$trackTitle' reached target duration (${recMs}ms >= ${expectedDurationMs - 200L}ms). Finalizing cleanly."
                                )
                                normalExit = true
                                isManualRecording = false
                                manualRecStateUpdater?.invoke(false)
                                finalizeCurrentRecording(reason = "Reached expected duration (${recMs}ms/${expectedDurationMs}ms)")
                                stateUpdater?.invoke(if (isArmed) EngineState.ARMED_LISTENING else EngineState.DISARMED)
                                break
                            }
                        } else if (read < 0) {
                            consecutiveErrors++
                            val errLabel = when (read) {
                                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE (-2)"
                                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT (-6, AudioServer / MediaProjection died)"
                                AudioRecord.ERROR -> "GENERIC_ERROR (-1)"
                                else -> "UNKNOWN_ERROR ($read)"
                            }
                            activeBridge?.log("SPOTIFY_REC_ERR", "⚠️ AudioRecord.read error: $errLabel ($consecutiveErrors/5)")
                            if (consecutiveErrors >= 5) {
                                val failReason = "AudioRecord read failed consecutively with $errLabel"
                                activeBridge?.log("SPOTIFY_REC_ERR", "🚨 Aborting take: $failReason")
                                normalExit = true
                                abortAndDiscard(failReason)
                                break
                            }
                            Thread.sleep(25)
                        }
                    }

                    if (!normalExit && isRecording) {
                        val state = audioRecord?.recordingState
                        val exitReason = if (state != AudioRecord.RECORDSTATE_RECORDING) {
                            "AudioRecord recordingState abruptly changed to $state (expected RECORDSTATE_RECORDING=3)"
                        } else {
                            "Loop terminated unexpectedly (isRecording=$isRecording, state=$state)"
                        }
                        activeBridge?.log("SPOTIFY_REC_STOPPED", "🛑 [THREAD LOOP EXITED] $exitReason. Flushing and cleaning state.")
                        abortAndDiscard(exitReason)
                    }
                } catch (e: Exception) {
                    val excReason = "Write buffer exception on '$trackTitle': ${e.message}"
                    activeBridge?.log("SPOTIFY_REC_ERR", "💥 $excReason")
                    abortAndDiscard(excReason)
                } finally {
                    try {
                        fos.flush()
                        fos.close()
                    } catch (_: Exception) {}
                }
            }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

        } catch (e: Exception) {
            activeBridge?.log("SPOTIFY_REC_ERR", "Failed starting AudioRecord for '$trackTitle': ${e.message}")
            isRecording = false
            stateUpdater?.invoke(EngineState.ARMED_LISTENING)
        }
    }

    private fun finalizeCurrentRecording(reason: String = "Normal completion") {
        var jobToTranscode: TranscodeJob? = null

        synchronized(recordLock) {
            if (!isRecording) return
            isRecording = false

            val take = activeTake
            activeTake = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                recordThread?.join(150)
            } catch (_: Exception) {}

            val recordedBytes = recordedBytesCount
            recordedBytesCount = 0L

            if (take == null) {
                activeBridge?.log("SPOTIFY_RECORDER", "⚠️ finalizeCurrentRecording called but activeTake was null.")
                return
            }

            val temp = take.tempFile
            val totalPcmBytes = (temp.length() - 44L).coerceAtLeast(0L)
            val recordedDurationMs = (totalPcmBytes * 1000L) / (44100 * 2 * 2)
            val targetLength = take.expectedDurationMs

            val isManual = take.trackId.startsWith("manual_")
            val isDurationComplete = if (isManual) {
                recordedDurationMs >= 1000L
            } else {
                targetLength > 0 && (
                    abs(recordedDurationMs - targetLength) <= 5000L ||
                    recordedDurationMs >= targetLength - 4000L
                )
            }

            activeBridge?.log(
                "SPOTIFY_RECORDER",
                "🏁 [FINALIZE TAKE] Reason: $reason | Track: '${take.trackTitle}' by '${take.artistName}' (ID: ${take.trackId}) | Recorded: ${recordedDurationMs}ms | Target: ${targetLength}ms | isDurationComplete=$isDurationComplete | wasInterrupted=$wasInterrupted"
            )

            if (isManualRecording) {
                isManualRecording = false
                manualRecStateUpdater?.invoke(false)
                activeBridge?.releaseWakeLock()
                activeBridge?.log("SPOTIFY_RECORDER", "ℹ️ Manual recording state cleared after finalize ($reason).")
            }

            if (!wasInterrupted && isDurationComplete) {
                val ctx = activeContext
                val vaultDir = if (ctx != null) getVaultDirectory(ctx) else null

                if (vaultDir != null) {
                    jobToTranscode = TranscodeJob(
                        tempPcmFile = temp,
                        vaultDir = vaultDir,
                        artist = take.artistName,
                        title = take.trackTitle,
                        trackId = take.trackId,
                        recordedDurationMs = recordedDurationMs,
                        targetLengthMs = targetLength
                    )
                } else {
                    temp.delete()
                }
            } else {
                temp.delete()
                countDiscarded++
                val failReason = if (wasInterrupted) "Recording interrupted" else "Duration incomplete (${recordedDurationMs / 1000}s vs ${targetLength / 1000}s expected)"
                val info = DiscardInfo(take.trackTitle, failReason)
                lastDiscardInfo = info
                discardUpdater?.invoke(info)
                statsUpdater?.invoke(countSaved, countDiscarded, countAds)
                activeBridge?.log(
                    "SPOTIFY_RECORDER",
                    "❌ Discarded take for '${take.trackTitle}' ($failReason)"
                )
            }
        }

        // Asynchronous non-blocking dispatch outside of lock (< 2ms total execution)
        jobToTranscode?.let { job ->
            activeBridge?.log(
                "SPOTIFY_VAULT",
                "⚡ [QUEUE TRANSCODE] Instant handoff of '${job.title}' by '${job.artist}' (ID: ${job.trackId}) to background transcode queue."
            )
            dispatchBackgroundTranscode(job)
        }
    }

    private fun dispatchBackgroundTranscode(job: TranscodeJob) {
        val saveInfo = SavingInfo(job.title, job.artist)
        activeSavingInfo = saveInfo
        savingUpdater?.invoke(saveInfo)

        transcodeScope.launch(transcodeDispatcher) {
            try {
                val cleanArtist = sanitizeFilename(job.artist)
                val cleanTitle = sanitizeFilename(job.title)
                val finalM4a = File(job.vaultDir, "$cleanArtist - $cleanTitle.m4a")

                activeBridge?.log(
                    "SPOTIFY_VAULT",
                    "⚙️ [TRANSCODE START] Processing '${job.title}' by '${job.artist}' | TrackID: ${job.trackId} | Destination: ${finalM4a.name}"
                )

                // 1. Fetch High-Res Album Art from Spotify oEmbed directly to memory
                val artworkBytes = fetchArtworkBytes(job.trackId)
                if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                    activeBridge?.log("SPOTIFY_ART", "🖼️ In-memory cover art retrieved for '${job.title}' (${artworkBytes.size / 1024} KB). Embedding directly into container.")
                } else {
                    activeBridge?.log("SPOTIFY_ART", "ℹ️ No cover art retrieved for Track ID: '${job.trackId}'")
                }

                val aacSuccess = encodePcmToAac(job.tempPcmFile, finalM4a, 44100, 2, 192000)
                val finalFile: File
                val savedOk: Boolean

                if (aacSuccess && finalM4a.exists() && finalM4a.length() > 1000) {
                    job.tempPcmFile.delete()
                    savedOk = true
                    finalFile = finalM4a
                    // 2. Inject MP4 metadata & covr atom into M4A container with frozen job identity
                    injectMp4Metadata(finalM4a, job.title, job.artist, artworkBytes)
                } else {
                    activeBridge?.log("SPOTIFY_WARN", "AAC hardware encoder fallback on '${job.title}'. Preserving WAV...")
                    writeWavHeader(job.tempPcmFile, 44100, 2, 16)
                    val finalWav = File(job.vaultDir, "$cleanArtist - $cleanTitle.wav")
                    savedOk = if (job.tempPcmFile.renameTo(finalWav)) true else {
                        try { job.tempPcmFile.copyTo(finalWav, overwrite = true); job.tempPcmFile.delete(); true } catch (_: Exception) { false }
                    }
                    finalFile = finalWav
                }

                if (savedOk) {
                    synchronized(recordLock) {
                        countSaved++
                    }
                    statsUpdater?.invoke(countSaved, countDiscarded, countAds)
                    vaultRefreshTrigger?.invoke()
                    val mbSize = String.format(Locale.US, "%.1f", finalFile.length() / (1024.0 * 1024.0))
                    activeBridge?.log(
                        "SPOTIFY_VAULT",
                        "✅ [TRANSCODE SUCCESS] Saved '${finalFile.name}' (${mbSize} MB, Duration: ${job.recordedDurationMs}ms). Verified Artist='${job.artist}', Title='${job.title}'"
                    )
                    activeBridge?.showToast("Saved: ${finalFile.name} (${mbSize} MB)")
                } else {
                    activeBridge?.log("SPOTIFY_ERR", "Failed to commit ${finalFile.name} to storage.")
                }
            } catch (e: Exception) {
                activeBridge?.log("SPOTIFY_ERR", "Background transcode error on [${job.title}]: ${e.message}")
                job.tempPcmFile.delete()
            } finally {
                if (activeSavingInfo?.title == job.title) {
                    activeSavingInfo = null
                    savingUpdater?.invoke(null)
                }
            }
        }
    }

    private fun fetchArtworkBytes(trackId: String): ByteArray? {
        val cleanId = trackId.removePrefix("spotify:track:").trim()
        if (cleanId.isEmpty()) return null
        return try {
            val oEmbedUrl = "https://open.spotify.com/oembed?url=https://open.spotify.com/track/$cleanId"
            val conn = java.net.URL(oEmbedUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            if (conn.responseCode in 200..299) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(jsonStr)
                val thumbUrl = json.optString("thumbnail_url", "")
                if (thumbUrl.isNotEmpty()) {
                    val imgConn = java.net.URL(thumbUrl).openConnection() as java.net.HttpURLConnection
                    imgConn.connectTimeout = 6000
                    imgConn.readTimeout = 6000
                    imgConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    if (imgConn.responseCode in 200..299) {
                        imgConn.inputStream.use { it.readBytes() }
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            activeBridge?.log("SPOTIFY_ART", "Artwork fetch exception for [$cleanId]: ${e.message}")
            null
        }
    }

    private fun buildBox(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val size = 8 + payload.size
        val buf = java.nio.ByteBuffer.allocate(size)
        buf.putInt(size)
        buf.put(typeBytes)
        buf.put(payload)
        return buf.array()
    }

    private fun buildTextDataBox(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = java.nio.ByteBuffer.allocate(8 + textBytes.size)
        payload.putInt(1) // type: UTF-8 text
        payload.putInt(0) // locale/padding
        payload.put(textBytes)
        return buildBox("data", payload.array())
    }

    private fun buildImageDataBox(imageBytes: ByteArray): ByteArray {
        val isPng = imageBytes.size >= 8 &&
            imageBytes[0] == 0x89.toByte() && imageBytes[1] == 0x50.toByte() &&
            imageBytes[2] == 0x4E.toByte() && imageBytes[3] == 0x47.toByte()
        val typeCode = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
        val payload = java.nio.ByteBuffer.allocate(8 + imageBytes.size)
        payload.putInt(typeCode)
        payload.putInt(0)
        payload.put(imageBytes)
        return buildBox("data", payload.array())
    }

    private fun injectMp4Metadata(
        m4aFile: File,
        title: String,
        artist: String,
        coverBytes: ByteArray?
    ) {
        if (!m4aFile.exists() || m4aFile.length() < 16) return
        try {
            val ilstPayload = java.io.ByteArrayOutputStream()
            if (title.isNotEmpty()) {
                ilstPayload.write(buildBox("\u00A9nam", buildTextDataBox(title)))
            }
            if (artist.isNotEmpty()) {
                ilstPayload.write(buildBox("\u00A9ART", buildTextDataBox(artist)))
            }
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                ilstPayload.write(buildBox("covr", buildImageDataBox(coverBytes)))
            }
            val ilstBox = buildBox("ilst", ilstPayload.toByteArray())

            val hdlrPayload = java.nio.ByteBuffer.allocate(25).apply {
                putInt(0)
                putInt(0)
                put("mdir".toByteArray(Charsets.US_ASCII))
                put("appl".toByteArray(Charsets.US_ASCII))
                putLong(0L)
                put(0.toByte())
            }
            val hdlrBox = buildBox("hdlr", hdlrPayload.array())

            val metaPayload = java.io.ByteArrayOutputStream().apply {
                write(ByteArray(4))
                write(hdlrBox)
                write(ilstBox)
            }
            val metaBox = buildBox("meta", metaPayload.toByteArray())
            val udtaBox = buildBox("udta", metaBox)

            RandomAccessFile(m4aFile, "rw").use { raf ->
                var pos = 0L
                val fileLen = raf.length()
                while (pos < fileLen) {
                    raf.seek(pos)
                    val boxSizeInt = raf.readInt()
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val boxType = String(typeBytes, Charsets.US_ASCII)

                    val boxSize = if (boxSizeInt == 1) {
                        raf.readLong()
                    } else if (boxSizeInt == 0) {
                        fileLen - pos
                    } else {
                        boxSizeInt.toLong() and 0xFFFFFFFFL
                    }

                    if (boxType == "moov") {
                        if (pos + boxSize >= fileLen) {
                            raf.seek(fileLen)
                            raf.write(udtaBox)
                            val newMoovSize = boxSize + udtaBox.size
                            raf.seek(pos)
                            if (boxSizeInt == 1) {
                                raf.writeInt(1)
                                raf.write("moov".toByteArray(Charsets.US_ASCII))
                                raf.writeLong(newMoovSize)
                            } else {
                                raf.writeInt(newMoovSize.toInt())
                            }
                            activeBridge?.log("SPOTIFY_ART", "🎨 Injected cover art & ID3 metadata into [${m4aFile.name}]")
                        }
                        break
                    }
                    pos += boxSize
                }
            }
        } catch (e: Exception) {
            activeBridge?.log("SPOTIFY_ERR", "Failed injecting MP4 metadata into [${m4aFile.name}]: ${e.message}")
        }
    }

    private fun encodePcmToAac(
        pcmFile: File,
        outputM4aFile: File,
        sampleRate: Int = 44100,
        channels: Int = 2,
        bitRate: Int = 192000
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            FileInputStream(pcmFile).use { fis ->
                if (pcmFile.length() > 44) {
                    fis.skip(44)
                }

                val buffer = ByteArray(4096)
                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false
                var presentationTimeUs = 0L

                while (true) {
                    if (!isEos) {
                        val inIndex = codec.dequeueInputBuffer(5000)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            inputBuffer?.clear()
                            val bytesRead = fis.read(buffer)
                            if (bytesRead <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEos = true
                            } else {
                                inputBuffer?.put(buffer, 0, bytesRead)
                                codec.queueInputBuffer(inIndex, 0, bytesRead, presentationTimeUs, 0)
                                presentationTimeUs += (bytesRead * 1_000_000L) / (sampleRate * channels * 2)
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEos) {
                        break
                    }
                }
            }

            return true
        } catch (e: Exception) {
            activeBridge?.log("SPOTIFY_ERR", "AAC Transcode error: ${e.message}")
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun abortAndDiscard(reason: String) {
        wasInterrupted = true
        isRecording = false

        if (isManualRecording) {
            isManualRecording = false
            manualRecStateUpdater?.invoke(false)
            activeBridge?.releaseWakeLock()
            activeBridge?.log("SPOTIFY_RECORDER", "ℹ️ Manual recording state cleared after abort ($reason).")
        }

        val take = activeTake
        activeTake = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}

        take?.tempFile?.delete()
        recordedBytesCount = 0L

        countDiscarded++
        val discardedTitle = take?.trackTitle ?: currentTrackTitle.ifEmpty { "Audio Stream" }
        val info = DiscardInfo(discardedTitle, reason)
        lastDiscardInfo = info
        discardUpdater?.invoke(info)

        statsUpdater?.invoke(countSaved, countDiscarded, countAds)
        stateUpdater?.invoke(EngineState.INTERRUPTED_DISCARDED)
        activeBridge?.log("SPOTIFY_RECORDER", "⚠️ Discard triggered for '$discardedTitle': $reason")
    }

    private fun writeWavHeader(file: File, sampleRate: Int = 44100, channels: Short = 2, bitsPerSample: Short = 16) {
        val totalAudioLen = (file.length() - 44L).coerceAtLeast(0L)
        val totalDataLen = totalAudioLen + 36L
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16))
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
            raf.writeShort(java.lang.Short.reverseBytes(channels).toInt())
            raf.writeInt(Integer.reverseBytes(sampleRate))
            raf.writeInt(Integer.reverseBytes(byteRate.toInt()))
            raf.writeShort(java.lang.Short.reverseBytes(blockAlign).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(bitsPerSample).toInt())
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
        }
    }

    @Composable
    fun SpotifyVaultScreen(context: Context, bridge: HostBridge) {
        var engineState by remember { mutableStateOf(SpotifyRecorderPlugin.engineState) }
        var trackTitle by remember { mutableStateOf(currentTrackTitle.ifEmpty { "Waiting for playback..." }) }
        var artistName by remember { mutableStateOf(currentArtist.ifEmpty { "Spotify Broadcast Radar" }) }
        var trackLen by remember { mutableLongStateOf(currentLengthMs) }
        var anchorPos by remember { mutableLongStateOf(currentPositionMs) }
        var isPlayingNow by remember { mutableStateOf(isPlayingTrack) }
        var currentDisplayPos by remember { mutableLongStateOf(currentPositionMs) }

        var isManualRecActive by remember { mutableStateOf(SpotifyRecorderPlugin.isManualRecording) }
        var selectedLimitMin by remember { mutableIntStateOf(0) }
        var customMinutesText by remember { mutableStateOf("") }
        var manualElapsedMs by remember { mutableLongStateOf(0L) }

        var savedStat by remember { mutableIntStateOf(countSaved) }
        var discardedStat by remember { mutableIntStateOf(countDiscarded) }
        var adsStat by remember { mutableIntStateOf(countAds) }
        var discardInfo by remember { mutableStateOf(lastDiscardInfo) }
        var savingInfo by remember { mutableStateOf(activeSavingInfo) }

        var vaultFiles by remember { mutableStateOf(listOf<VaultTrack>()) }

        fun cleanOrphanedFiles(dir: File) {
            try {
                val now = System.currentTimeMillis()
                val activeTempName = activeTake?.tempFile?.name
                val activeTempPath = activeTake?.tempFile?.absolutePath

                dir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        val n = f.name
                        if (n.startsWith(".recording_") && n.endsWith(".tmp")) {
                            val isActiveTake = f.name == activeTempName || f.absolutePath == activeTempPath
                            val isStale = (now - f.lastModified()) > 30 * 60 * 1000L
                            if (!isActiveTake && isStale) {
                                f.delete()
                                activeBridge?.log("SPOTIFY_VAULT", "🧹 Purged stale orphaned temp file: $n")
                            }
                        } else if (n.endsWith(".jpg", ignoreCase = true) || n.endsWith(".png", ignoreCase = true)) {
                            if ((now - f.lastModified()) > 5 * 60 * 1000L) {
                                f.delete()
                                activeBridge?.log("SPOTIFY_VAULT", "🧹 Cleaned up redundant thumbnail image: $n")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        fun playTrack(trackFile: File) {
            try {
                if (!trackFile.exists() || trackFile.length() == 0L) {
                    bridge.showToast("Track file is empty or missing from storage.")
                    return
                }

                var uri: Uri? = null
                try {
                    uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        trackFile
                    )
                } catch (fe: Exception) {
                    activeBridge?.log("SPOTIFY_PLAY_WARN", "FileProvider failed (${fe.message}). Falling back to StrictMode file URI...")
                }

                if (uri == null) {
                    try {
                        val builder = android.os.StrictMode.VmPolicy.Builder()
                        android.os.StrictMode.setVmPolicy(builder.build())
                        uri = Uri.fromFile(trackFile)
                    } catch (_: Exception) {}
                }

                if (uri == null) {
                    bridge.showToast("Could not resolve URI for playback.")
                    return
                }

                val mime = if (trackFile.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/*"
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    clipData = android.content.ClipData.newRawUri("Audio", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val songTitle = trackFile.name.removeSuffix(".m4a").removeSuffix(".wav")
                val chooser = Intent.createChooser(playIntent, "Play '$songTitle'").apply {
                    clipData = android.content.ClipData.newRawUri("Audio", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                activeBridge?.log("SPOTIFY_PLAY", "✅ Dispatched player chooser for: ${trackFile.name} (URI: $uri)")
            } catch (e: Exception) {
                activeBridge?.log("SPOTIFY_PLAY_ERR", "Player intent error: ${e.message}")
                bridge.showToast("Could not open player: ${e.message}")
            }
        }

        fun reloadVaultList() {
            val dir = getVaultDirectory(context)
            cleanOrphanedFiles(dir)
            cleanOrphanedFiles(getStagingDirectory(context))
            val files = dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".m4a") || it.name.endsWith(".wav")) }
                ?.map { file ->
                    var dur = 0L
                    var artBytes: ByteArray? = null
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(file.absolutePath)
                        dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        artBytes = mmr.embeddedPicture
                    } catch (_: Exception) {}
                    finally {
                        try { mmr.release() } catch (_: Exception) {}
                    }
                    VaultTrack(file, file.name, file.length(), file.lastModified(), dur, artBytes)
                }
                ?.sortedByDescending { it.modifiedAt } ?: emptyList()
            vaultFiles = files
            activeBridge?.log("SPOTIFY_VAULT", "Vault refreshed: ${files.size} track(s) discovered in ${dir.absolutePath}")
        }

        // Real-Time Monotonic Position Interpolator & Manual Elapsed Timer
        LaunchedEffect(isPlayingNow, anchorPos, trackLen) {
            val baseTime = SystemClock.elapsedRealtime()
            while (true) {
                isManualRecActive = SpotifyRecorderPlugin.isManualRecording
                if (isManualRecActive) {
                    val takeStart = activeTake?.startedAtMs ?: SystemClock.elapsedRealtime()
                    manualElapsedMs = SystemClock.elapsedRealtime() - takeStart
                }

                if (isPlayingNow && trackLen > 0) {
                    val elapsed = SystemClock.elapsedRealtime() - baseTime
                    val computed = anchorPos + elapsed
                    currentDisplayPos = if (computed >= trackLen - 1000L) trackLen else computed.coerceAtMost(trackLen)
                } else {
                    currentDisplayPos = anchorPos.coerceAtMost(trackLen)
                }
                delay(150)
            }
        }

        LaunchedEffect(Unit) {
            stateUpdater = { engineState = it }
            manualRecStateUpdater = { isManualRecActive = it }
            trackMetaUpdater = { t, a, l, p, isPlay ->
                trackTitle = t
                artistName = a
                trackLen = l
                anchorPos = p
                isPlayingNow = isPlay
            }
            statsUpdater = { s, d, ad ->
                savedStat = s
                discardedStat = d
                adsStat = ad
            }
            discardUpdater = { discardInfo = it }
            savingUpdater = { savingInfo = it }
            vaultRefreshTrigger = { reloadVaultList() }

            ensureReceiverRegistered(context)
            reloadVaultList()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF191414)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎧", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Spotify Recorder", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Autonomous Internal Stream Ripper", fontSize = 11.sp, color = Color(0xFF8B949E))
                    }
                }

                IconButton(onClick = { reloadVaultList() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Vault", tint = Color(0xFF58A6FF))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Main Radar & Arming Card: Verified against real MediaProjection token
            val isEngineArmed = isArmed && mediaProjection != null && engineState != EngineState.DISARMED
            val isAdShieldActive = engineState == EngineState.SKIPPING_AD
            val cardBorderColor = when {
                isAdShieldActive -> Color(0xFFD29922)
                isEngineArmed -> Color(0xFF1DB954).copy(alpha = 0.8f)
                else -> Color.White.copy(alpha = 0.08f)
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(if (isAdShieldActive) 2.dp else 1.dp, cardBorderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Strict hardware check: Only pulse when audio buffers are physically streaming
                    val isActivelyRecording = (engineState == EngineState.RECORDING || engineState == EngineState.MANUAL_RECORDING) &&
                        isRecording &&
                        audioRecord != null &&
                        audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

                    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.35f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseScale"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .graphicsLayer {
                                        if (isActivelyRecording) {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                            alpha = pulseAlpha
                                        }
                                    }
                                    .clip(CircleShape)
                                    .background(
                                        if (isActivelyRecording) Color(0xFFF85149) else engineState.color
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isActivelyRecording) {
                                    if (isManualRecActive) "REC • MANUAL AUDIO CAPTURE" else "REC • CAPTURING CLEAN STREAM"
                                } else {
                                    engineState.label
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isActivelyRecording) Color(0xFFF85149) else engineState.color
                            )
                        }

                        Button(
                            onClick = { if (isEngineArmed) disarmEngine(context, bridge) else armEngine(context, bridge) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEngineArmed) Color(0xFFDA3633) else Color(0xFF1DB954)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                if (isEngineArmed) "ENGINE ARMED" else "ARM ENGINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Background Transcoding / Saving Pipeline Banner
                    AnimatedVisibility(visible = savingInfo != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF58A6FF).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF58A6FF).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("⚙️", fontSize = 13.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "SAVING & ENCODING: ${savingInfo?.title}",
                                            color = Color(0xFF58A6FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "AAC 192k",
                                        color = Color(0xFF8B949E),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF58A6FF),
                                    trackColor = Color(0xFF21262D)
                                )
                            }
                        }
                    }

                    // Contextual Discard Alert HUD
                    AnimatedVisibility(visible = discardInfo != null && !isActivelyRecording) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF85149).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFFF85149).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("❌", fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "DISCARDED: ${discardInfo?.title}",
                                        color = Color(0xFFF85149),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "Reason: ${discardInfo?.reason}",
                                        color = Color(0xFFFFD2D2),
                                        fontSize = 10.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }

                    // Visual Ad Alert Banner
                    if (isAdShieldActive) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFD29922).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFFD29922).copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🛡️", fontSize = 14.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Commercial Ad Shield Active — Stream Bypassed",
                                    color = Color(0xFFD29922),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Live Track Info
                    Text(
                        text = trackTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    Text(
                        text = artistName,
                        color = if (isAdShieldActive) Color(0xFFD29922) else Color(0xFF8B949E),
                        fontSize = 12.sp,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(12.dp))

                    // Live Moving Real-Time Duration Bar
                    val progress = if (trackLen > 0) (currentDisplayPos.toFloat() / trackLen.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isAdShieldActive) Color(0xFFD29922) else Color(0xFF1DB954),
                        trackColor = Color(0xFF21262D)
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMs(currentDisplayPos), fontSize = 10.sp, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace)
                        Text(formatMs(trackLen), fontSize = 10.sp, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Universal Audio Capture Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(
                    1.dp,
                    if (isManualRecActive) Color(0xFFFF7B72) else Color.White.copy(alpha = 0.08f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isManualRecActive) Color(0xFFFF7B72) else Color(0xFF58A6FF))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isManualRecActive) "RECORDING DEVICE AUDIO" else "UNIVERSAL AUDIO CAPTURE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isManualRecActive) Color(0xFFFF7B72) else Color(0xFF58A6FF)
                            )
                        }

                        if (isManualRecActive) {
                            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFF7B72).copy(alpha = 0.2f)) {
                                Text(
                                    text = formatMs(manualElapsedMs) + if (selectedLimitMin > 0) " / ${selectedLimitMin}m" else "",
                                    color = Color(0xFFFF7B72),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Capture whatever audio is playing (YouTube, Browser, Games, Music)",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    if (!isManualRecActive) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(0 to "No Limit", 5 to "5m", 15 to "15m", 30 to "30m").forEach { (mins, label) ->
                                val isSel = selectedLimitMin == mins && customMinutesText.isEmpty()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFF58A6FF).copy(alpha = 0.25f) else Color(0xFF21262D),
                                    border = BorderStroke(1.dp, if (isSel) Color(0xFF58A6FF) else Color(0xFF30363D)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedLimitMin = mins
                                            customMinutesText = ""
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            label,
                                            color = if (isSel) Color(0xFF58A6FF) else Color(0xFFC9D1D9),
                                            fontSize = 10.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            // Custom exact numeric minute input box
                            OutlinedTextField(
                                value = customMinutesText,
                                onValueChange = { newVal ->
                                    val filtered = newVal.filter { it.isDigit() }.take(4)
                                    customMinutesText = filtered
                                    val parsed = filtered.toIntOrNull()
                                    if (parsed != null && parsed > 0) {
                                        selectedLimitMin = parsed
                                    } else if (filtered.isEmpty()) {
                                        selectedLimitMin = 0
                                    }
                                },
                                placeholder = {
                                    Text(
                                        "min",
                                        fontSize = 10.sp,
                                        color = Color(0xFF8B949E),
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(44.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF58A6FF),
                                    unfocusedBorderColor = if (customMinutesText.isNotEmpty()) Color(0xFF58A6FF) else Color(0xFF30363D),
                                    focusedContainerColor = Color(0xFF21262D),
                                    unfocusedContainerColor = Color(0xFF21262D)
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            if (isManualRecActive) {
                                stopManualRecording(bridge)
                            } else {
                                startManualRecording(context, bridge, selectedLimitMin)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isManualRecActive) Color(0xFFDA3633) else Color(0xFF1F6FEB)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Icon(
                            if (isManualRecActive) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isManualRecActive) "STOP & SAVE RECORDING" else "START AUDIO RECORDING",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Metrics Grid (Saved, Discarded, Ads)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard("Saved Perfect", "$savedStat", Color(0xFF1DB954), Modifier.weight(1f))
                MetricCard("Discarded (Seek/Cut)", "$discardedStat", Color(0xFFF85149), Modifier.weight(1f))
                MetricCard("Ads Skipped", "$adsStat", Color(0xFFD29922), Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))

            // Vault List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recorded Tracks (${vaultFiles.size})", color = Color(0xFFC9D1D9), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Music/Omni Spotify", color = Color(0xFF8B949E), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(8.dp))

            // Vault Recordings List
            if (vaultFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📻", fontSize = 28.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("No completed tracks saved yet", color = Color(0xFF8B949E), fontSize = 13.sp)
                        Text("Turn on 'Device Broadcast Status' in Spotify Settings", color = Color(0xFF484F58), fontSize = 11.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vaultFiles, key = { it.file.absolutePath }) { track ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { playTrack(track.file) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Dynamic Album Art Thumbnail from embedded MP4 picture metadata
                                val coverBitmap = remember(track.file.absolutePath) {
                                    track.coverBytes?.let { bytes ->
                                        try {
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                }

                                if (coverBitmap != null) {
                                    Image(
                                        bitmap = coverBitmap,
                                        contentDescription = track.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF21262D)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🎵", fontSize = 18.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.name.removeSuffix(".m4a").removeSuffix(".wav"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (track.durationMs > 0) {
                                            Text(
                                                formatMs(track.durationMs),
                                                color = Color(0xFF1DB954),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        val mbStr = String.format(Locale.US, "%.1f MB", track.sizeBytes / (1024.0 * 1024.0))
                                        Text(mbStr, color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Text(SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(track.modifiedAt)), color = Color(0xFF8B949E), fontSize = 10.sp)
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { playTrack(track.file) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Track", tint = Color(0xFF1DB954), modifier = Modifier.size(18.dp))
                                    }

                                    IconButton(
                                        onClick = {
                                            track.file.delete()
                                            reloadVaultList()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF85149).copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = modifier
        ) {
            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(2.dp))
                Text(title, color = Color(0xFF8B949E), fontSize = 9.sp, maxLines = 1)
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    companion object {
        @Volatile var activeContext: Context? = null
        @Volatile var activeBridge: HostBridge? = null
        @Volatile var activePluginInstance: SpotifyRecorderPlugin? = null

        var mediaProjection: MediaProjection? = null
        var audioRecord: AudioRecord? = null
        @Volatile var isRecording = false
        var recordThread: Thread? = null

        @Volatile var activeTake: ActiveRecordingTake? = null

        @Volatile var isArmed = false
        @Volatile var currentTrackId = ""
        @Volatile var currentTrackTitle = ""
        @Volatile var currentArtist = ""
        @Volatile var currentLengthMs = 0L
        @Volatile var currentPositionMs = 0L
        @Volatile var isPlayingTrack = false
        @Volatile var lastSyncTimestamp = 0L
        @Volatile var wasInterrupted = false
        @Volatile var recordedBytesCount = 0L

        @Volatile var isAdActive = false
        @Volatile var adTitle = ""
        @Volatile var adArtist = ""

        @Volatile var isManualRecording = false
        @Volatile var manualRecordingLimitMs = 0L
        @Volatile var isVoluntaryProjectionStop = false

        var stateUpdater: ((EngineState) -> Unit)? = null
        var manualRecStateUpdater: ((Boolean) -> Unit)? = null
        var trackMetaUpdater: ((title: String, artist: String, lengthMs: Long, posMs: Long, isPlaying: Boolean) -> Unit)? = null
        var statsUpdater: ((saved: Int, discarded: Int, ads: Int) -> Unit)? = null
        var vaultRefreshTrigger: (() -> Unit)? = null
        @Volatile var lastDiscardInfo: DiscardInfo? = null
        @Volatile var activeSavingInfo: SavingInfo? = null
        var discardUpdater: ((DiscardInfo?) -> Unit)? = null
        var savingUpdater: ((SavingInfo?) -> Unit)? = null

        fun registerProjectionCallback(projection: MediaProjection) {
            try {
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        if (isVoluntaryProjectionStop) {
                            activeBridge?.log("SPOTIFY_PROJECTION", "ℹ️ MediaProjection onStop() fired voluntarily during disarm.")
                            isVoluntaryProjectionStop = false
                            return
                        }
                        val stopMsg = "Android OS revoked MediaProjection (Screen locked, permission expired, or FGS downgraded)"
                        activeBridge?.log("SPOTIFY_PROJECTION_STOP", "🚨 [MEDIA_PROJECTION REVOKED] $stopMsg. wasRecording=$isRecording, isManual=$isManualRecording")
                        handleProjectionRevoked(stopMsg)
                    }
                }
                projection.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
                activeBridge?.log("SPOTIFY_PROJECTION", "🛡️ Registered MediaProjection.Callback watchdog on active token.")
            } catch (e: Exception) {
                activeBridge?.log("SPOTIFY_PROJECTION_ERR", "⚠️ Failed registering projection callback: ${e.message}")
            }
        }

        fun handleProjectionRevoked(reason: String) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val wasManual = isManualRecording
                val wasRec = isRecording
                activeBridge?.log("SPOTIFY_RECORDER", "🛑 [PROJECTION CLEANUP] Disengaging pipeline. Reason: $reason (wasManual=$wasManual, wasRecording=$wasRec)")

                if (wasRec) {
                    activePluginInstance?.abortAndDiscard("Projection revoked: $reason")
                }

                isManualRecording = false
                manualRecStateUpdater?.invoke(false)
                isRecording = false
                mediaProjection = null
                isArmed = false
                engineState = EngineState.DISARMED
                stateUpdater?.invoke(engineState)
                activeBridge?.releaseWakeLock()
                activeBridge?.stopProjectionService()

                val ctx = activeContext
                if (ctx != null) {
                    setDaemonState(ctx, false)
                }
                activeBridge?.showToast("⚠️ Capture Disengaged: $reason")
            }
        }

        var countSaved = 0
        var countDiscarded = 0
        var countAds = 0
        val recordLock = Any()
        val transcodeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val transcodeDispatcher = Dispatchers.IO.limitedParallelism(1)
        @Volatile var pauseDebounceJob: Job? = null
        @Volatile var isReceiverRegistered = false
        @Volatile var engineState = EngineState.DISARMED

        val spotifyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    "com.spotify.music.metadatachanged",
                    "com.spotify.mobile.android.metadatachanged" -> activePluginInstance?.handleMetadataChanged(intent)
                    "com.spotify.music.playbackstatechanged",
                    "com.spotify.mobile.android.playbackstatechanged" -> activePluginInstance?.handlePlaybackStateChanged(intent)
                }
            }
        }

        fun ensureReceiverRegistered(context: Context) {
            if (isReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction("com.spotify.music.metadatachanged")
                addAction("com.spotify.music.playbackstatechanged")
                addAction("com.spotify.music.queuechanged")
                addAction("com.spotify.mobile.android.metadatachanged")
                addAction("com.spotify.mobile.android.playbackstatechanged")
                addAction("com.spotify.mobile.android.queuechanged")
            }
            val appContext = context.applicationContext
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(spotifyReceiver, filter)
                }
                isReceiverRegistered = true
                activeBridge?.log("SPOTIFY_RADAR", "Persistent Spotify broadcast listener registered.")
            } catch (e: Exception) {
                activeBridge?.log("SPOTIFY_ERR", "Error mounting receiver: ${e.message}")
            }
        }

        fun startManualRecording(context: Context, bridge: HostBridge, limitMinutes: Int) {
            val limitMs = if (limitMinutes > 0) limitMinutes * 60_000L else 0L
            manualRecordingLimitMs = limitMs

            fun proceedCapture() {
                val ctx = activeContext ?: context
                val vaultDir = getVaultDirectory(ctx)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val trackTitle = "Capture_$timeStamp"
                val artistName = "Device Audio"

                if (isRecording) {
                    activePluginInstance?.finalizeCurrentRecording(reason = "Switching to manual recording")
                }

                isManualRecording = true
                isArmed = true
                val stagingDir = getStagingDirectory(ctx)
                activePluginInstance?.startAudioRecording(
                    stagingDir = stagingDir,
                    trackId = "manual_$timeStamp",
                    trackTitle = trackTitle,
                    artistName = artistName,
                    expectedDurationMs = limitMs
                )
                engineState = EngineState.MANUAL_RECORDING
                stateUpdater?.invoke(EngineState.MANUAL_RECORDING)
                bridge.acquireWakeLock("UniversalAudioCapture")
                bridge.showToast("🎙️ Capturing internal device audio...")
            }

            if (mediaProjection != null) {
                proceedCapture()
            } else {
                bridge.requestPermission(android.Manifest.permission.RECORD_AUDIO) { audioGranted ->
                    if (!audioGranted) {
                        bridge.showToast("Record Audio permission required for capture.")
                        return@requestPermission
                    }
                    bridge.requestMediaProjection { resultCode, data ->
                        if (resultCode == Activity.RESULT_OK && data != null) {
                            bridge.startProjectionService(
                                resultCode = resultCode,
                                data = data,
                                title = "Omni Audio Recorder Active",
                                message = "Capturing internal audio stream..."
                            ) { mp ->
                                if (mp != null) {
                                    registerProjectionCallback(mp)
                                    mediaProjection = mp
                                    proceedCapture()
                                } else {
                                    bridge.showToast("Failed to initialize audio capture projection.")
                                }
                            }
                        } else {
                            bridge.showToast("Media projection consent rejected.")
                        }
                    }
                }
            }
        }

        fun stopManualRecording(bridge: HostBridge) {
            if (!isManualRecording) return
            isManualRecording = false
            manualRecStateUpdater?.invoke(false)
            activePluginInstance?.finalizeCurrentRecording(reason = "User stopped manual recording")
            engineState = if (isArmed) EngineState.ARMED_LISTENING else EngineState.DISARMED
            stateUpdater?.invoke(engineState)
            bridge.releaseWakeLock()
            bridge.showToast("✅ Audio recording saved to Vault!")
        }

        fun setDaemonState(context: Context, enabled: Boolean) {
            try {
                val daemonPrefs = context.getSharedPreferences("omni_daemon_registry", Context.MODE_PRIVATE)
                val jsonStr = daemonPrefs.getString("active_daemons", "{}") ?: "{}"
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { JSONObject() }
                if (enabled) {
                    json.put("spotify_recorder", "com.omni.plugin.spotify.SpotifyRecorderPlugin")
                } else {
                    json.remove("spotify_recorder")
                }
                daemonPrefs.edit().putString("active_daemons", json.toString()).apply()
            } catch (_: Exception) {}
        }

        fun disarmEngine(context: Context, bridge: HostBridge) {
            if (isRecording) activePluginInstance?.finalizeCurrentRecording(reason = "User disarmed engine")
            isArmed = false
            isManualRecording = false
            manualRecStateUpdater?.invoke(false)
            engineState = EngineState.DISARMED
            stateUpdater?.invoke(EngineState.DISARMED)
            pauseDebounceJob?.cancel()
            pauseDebounceJob = null
            try {
                context.applicationContext.unregisterReceiver(spotifyReceiver)
                isReceiverRegistered = false
            } catch (_: Exception) {}
            isVoluntaryProjectionStop = true
            try { mediaProjection?.stop() } catch (_: Exception) {}
            mediaProjection = null
            bridge.stopProjectionService()
            bridge.releaseWakeLock()
            setDaemonState(context, false)
            bridge.showToast("Spotify Recorder Disarmed")
        }

        fun armEngine(context: Context, bridge: HostBridge) {
            bridge.requestPermission(android.Manifest.permission.RECORD_AUDIO) { audioGranted ->
                if (!audioGranted) {
                    bridge.showToast("Record Audio permission required for capture.")
                    return@requestPermission
                }

                bridge.requestMediaProjection { resultCode, data ->
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        bridge.startProjectionService(
                            resultCode = resultCode,
                            data = data,
                            title = "Spotify Recorder Active",
                            message = "Listening to internal media stream..."
                        ) { mp ->
                            if (mp != null) {
                                registerProjectionCallback(mp)
                                mediaProjection = mp
                                isArmed = true
                                engineState = EngineState.ARMED_LISTENING
                                stateUpdater?.invoke(EngineState.ARMED_LISTENING)
                                ensureReceiverRegistered(context)
                                bridge.acquireWakeLock("SpotifyRecorderSentinel")
                                setDaemonState(context, true)
                                bridge.showToast("Spotify Radar Armed! Ready to capture in background.")
                            } else {
                                bridge.showToast("Failed to initialize MediaProjection.")
                            }
                        }
                    } else {
                        bridge.showToast("Media projection consent rejected.")
                    }
                }
            }
        }
    }
}