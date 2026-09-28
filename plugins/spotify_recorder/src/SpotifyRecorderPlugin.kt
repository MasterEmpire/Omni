package com.omni.plugin.spotify

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import androidx.compose.animation.AnimatedVisibility
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

enum class EngineState(val label: String, val color: Color) {
    DISARMED("ENGINE OFFLINE", Color(0xFF8B949E)),
    ARMED_LISTENING("RADAR ACTIVE (WAITING NEXT TRACK)", Color(0xFF1DB954)),
    RECORDING("CAPTURING CLEAN STREAM", Color(0xFF58A6FF)),
    SKIPPING_AD("AD DETECTED (IGNORING)", Color(0xFFD29922)),
    WAITING_CLEAN_START("JOINED MID-TRACK (WAITING FOR 0:00)", Color(0xFFBC8CFF)),
    ALREADY_EXISTS("SONG ALREADY RECORDED", Color(0xFF388BFD)),
    INTERRUPTED_DISCARDED("INTERRUPTED (DISCARDED)", Color(0xFFF85149))
}

data class VaultTrack(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

class SpotifyRecorderPlugin : PluginEntry() {

    private var activeContext: Context? = null
    private var activeBridge: HostBridge? = null

    // Projection & Audio
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null

    // Track Execution State
    @Volatile private var isArmed = false
    @Volatile private var currentTrackId = ""
    @Volatile private var currentTrackTitle = ""
    @Volatile private var currentArtist = ""
    @Volatile private var currentLengthMs = 0L
    @Volatile private var currentPositionMs = 0L
    @Volatile private var wasInterrupted = false
    @Volatile private var tempRecordingFile: File? = null
    @Volatile private var recordedBytesCount = 0L

    // UI Reactive State Bridges
    private var stateUpdater: ((EngineState) -> Unit)? = null
    private var trackMetaUpdater: ((title: String, artist: String, lengthMs: Long, posMs: Long) -> Unit)? = null
    private var statsUpdater: ((saved: Int, discarded: Int, ads: Int) -> Unit)? = null
    private var vaultRefreshTrigger: (() -> Unit)? = null

    private var countSaved = 0
    private var countDiscarded = 0
    private var countAds = 0

    private val spotifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            activeBridge?.log("SPOTIFY_RX", "📥 Broadcast [${action}]")
            if (!isArmed) return
            when (action) {
                "com.spotify.music.metadatachanged",
                "com.spotify.mobile.android.metadatachanged" -> handleMetadataChanged(intent)
                "com.spotify.music.playbackstatechanged",
                "com.spotify.mobile.android.playbackstatechanged" -> handlePlaybackStateChanged(intent)
                else -> activeBridge?.log("SPOTIFY_RX", "Ignored action: $action")
            }
        }
    }

    private fun getVaultDirectory(context: Context): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val vaultDir = File(musicDir, "Omni Spotify")
        if (!vaultDir.exists()) vaultDir.mkdirs()
        return if (vaultDir.canWrite()) vaultDir else File(context.filesDir, "omni_spotify_vault").apply { if (!exists()) mkdirs() }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun handleMetadataChanged(intent: Intent) {
        val newTrackId = intent.getStringExtra("id") ?: ""
        val newTrack = intent.getStringExtra("track")
            ?: intent.getStringExtra("title")
            ?: "Unknown Track"
        val newArtist = intent.getStringExtra("artist") ?: "Unknown Artist"

        val rawLength = intent.getIntExtra("length", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("length", 0L)
        // Normalize length: if <= 10000, Spotify sent duration in seconds -> convert to ms
        val newLengthMs = if (rawLength in 1..10000) rawLength * 1000L else rawLength
        val newPos = intent.getIntExtra("playbackPosition", 0).toLong()
        val isPlaying = intent.getBooleanExtra("playing", true)

        activeBridge?.log("SPOTIFY_RADAR", "Metadata: '$newTrack' by '$newArtist' (ID: $newTrackId, Pos: ${newPos}ms, Len: ${newLengthMs}ms, Playing: $isPlaying)")

        // 1. If currently recording previous track, finalize & evaluate
        if (isRecording) {
            finalizeCurrentRecording()
        }

        currentTrackId = newTrackId
        currentTrackTitle = newTrack
        currentArtist = newArtist
        currentLengthMs = newLengthMs
        currentPositionMs = newPos
        trackMetaUpdater?.invoke(newTrack, newArtist, newLengthMs, newPos)

        // 2. Ad Detection (explicit ad ID or short advertisement duration)
        val isExplicitAd = newTrackId.contains(":ad:") || newTrack.equals("Advertisement", ignoreCase = true)
        val isShortAd = newLengthMs in 1..25000L && (isExplicitAd || newTrackId.isEmpty() || newTrack.contains("Spotify", ignoreCase = true))

        if (isExplicitAd || isShortAd) {
            countAds++
            statsUpdater?.invoke(countSaved, countDiscarded, countAds)
            stateUpdater?.invoke(EngineState.SKIPPING_AD)
            activeBridge?.log("SPOTIFY_RADAR", "Shield active: Ad detected ($newTrackId). Skipping.")
            return
        }

        // 3. Duplicate Vault Check
        val ctx = activeContext ?: return
        val vaultDir = getVaultDirectory(ctx)
        val targetName = "${sanitizeFilename(newArtist)} - ${sanitizeFilename(newTrack)}.wav"
        val existingFile = File(vaultDir, targetName)
        if (existingFile.exists() && existingFile.length() > 44) {
            stateUpdater?.invoke(EngineState.ALREADY_EXISTS)
            activeBridge?.log("SPOTIFY_RADAR", "Track already in vault: $targetName")
            return
        }

        // 4. Clean Start Policy (Must begin from the very first second: <= 1200ms)
        if (newPos > 1200L) {
            stateUpdater?.invoke(EngineState.WAITING_CLEAN_START)
            activeBridge?.log("SPOTIFY_RADAR", "Mid-track start (${newPos}ms). Waiting for next clean 0:00 track.")
            return
        }

        // 5. Conditions met: Launch Audio Stream Capture
        if (isPlaying && mediaProjection != null) {
            startAudioRecording(vaultDir, targetName, newLength)
        }
    }

    private fun handlePlaybackStateChanged(intent: Intent) {
        val isPlaying = intent.getBooleanExtra("playing", false)
        val pos = intent.getIntExtra("playbackPosition", -1).toLong()

        val fallbackTrack = intent.getStringExtra("track") ?: intent.getStringExtra("title")
        val fallbackArtist = intent.getStringExtra("artist")
        if (!fallbackTrack.isNullOrEmpty()) currentTrackTitle = fallbackTrack
        if (!fallbackArtist.isNullOrEmpty()) currentArtist = fallbackArtist

        activeBridge?.log("SPOTIFY_RADAR", "State: playing=$isPlaying, pos=${pos}ms, track='$currentTrackTitle'")

        if (pos >= 0) {
            currentPositionMs = pos
            trackMetaUpdater?.invoke(currentTrackTitle, currentArtist, currentLengthMs, pos)
        }

        if (isRecording) {
            if (!isPlaying) {
                activeBridge?.log("SPOTIFY_RADAR", "Playback paused mid-song! Discarding incomplete track.")
                abortAndDiscard("Paused mid-play")
            } else if (pos >= 0 && currentLengthMs > 0) {
                // Seek detection: Check if position drastically jumped away from real time
                val recordedMs = (recordedBytesCount * 1000L) / (44100 * 2 * 2)
                if (abs(pos - recordedMs) > 4000L) {
                    activeBridge?.log("SPOTIFY_RADAR", "Timeline seek detected! Discarding incomplete track.")
                    abortAndDiscard("Seek detected")
                }
            }
        }
    }

    private fun startAudioRecording(vaultDir: File, targetFilename: String, expectedDurationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) return

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

            val tempFile = File(vaultDir, ".recording_${System.currentTimeMillis()}.tmp")
            val fos = FileOutputStream(tempFile)
            fos.write(ByteArray(44)) // 44-byte dummy WAV header placeholder

            audioRecord = record
            tempRecordingFile = tempFile
            recordedBytesCount = 0L
            wasInterrupted = false
            isRecording = true

            record.startRecording()
            stateUpdater?.invoke(EngineState.RECORDING)
            activeBridge?.log("SPOTIFY_RECORDER", "AudioRecord armed. Capturing stream to ${tempFile.name}")

            recordThread = Thread {
                val buffer = ByteArray(minBuf)
                try {
                    while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            fos.write(buffer, 0, read)
                            recordedBytesCount += read
                        }
                    }
                } catch (e: Exception) {
                    activeBridge?.log("SPOTIFY_REC_ERR", "Write buffer exception: ${e.message}")
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
            activeBridge?.log("SPOTIFY_REC_ERR", "Failed starting AudioRecord: ${e.message}")
            isRecording = false
            stateUpdater?.invoke(EngineState.ARMED_LISTENING)
        }
    }

    private fun finalizeCurrentRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordThread?.join(500)
        } catch (_: Exception) {}

        val temp = tempRecordingFile ?: return
        val totalPcmBytes = (temp.length() - 44L).coerceAtLeast(0L)
        val recordedDurationMs = (totalPcmBytes * 1000L) / (44100 * 2 * 2)

        // Tolerance window: within 2.5 seconds of metadata duration
        val isDurationComplete = currentLengthMs > 0 && abs(recordedDurationMs - currentLengthMs) <= 2500L

        if (!wasInterrupted && isDurationComplete) {
            writeWavHeader(temp, 44100, 2, 16)
            val ctx = activeContext ?: return
            val vaultDir = getVaultDirectory(ctx)
            val finalTarget = File(vaultDir, "${sanitizeFilename(currentArtist)} - ${sanitizeFilename(currentTrackTitle)}.wav")
            temp.renameTo(finalTarget)

            countSaved++
            statsUpdater?.invoke(countSaved, countDiscarded, countAds)
            vaultRefreshTrigger?.invoke()
            activeBridge?.log("SPOTIFY_RECORDER", "✅ [PERFECT TAKE] Saved: ${finalTarget.name} (${finalTarget.length() / 1024} KB)")
            activeBridge?.showToast("Saved: ${finalTarget.name}")
        } else {
            temp.delete()
            countDiscarded++
            statsUpdater?.invoke(countSaved, countDiscarded, countAds)
            activeBridge?.log("SPOTIFY_RECORDER", "❌ Discarded take (Recorded ${recordedDurationMs}ms vs Expected ${currentLengthMs}ms, Interrupted: $wasInterrupted)")
        }

        tempRecordingFile = null
        recordedBytesCount = 0L
    }

    private fun abortAndDiscard(reason: String) {
        wasInterrupted = true
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}

        tempRecordingFile?.delete()
        tempRecordingFile = null
        recordedBytesCount = 0L

        countDiscarded++
        statsUpdater?.invoke(countSaved, countDiscarded, countAds)
        stateUpdater?.invoke(EngineState.INTERRUPTED_DISCARDED)
        activeBridge?.log("SPOTIFY_RECORDER", "Discard triggered: $reason")
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

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        activeContext = context
        activeBridge = bridge

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

    @Composable
    fun SpotifyVaultScreen(context: Context, bridge: HostBridge) {
        var engineState by remember { mutableStateOf(if (isArmed) EngineState.ARMED_LISTENING else EngineState.DISARMED) }
        var trackTitle by remember { mutableStateOf(currentTrackTitle.ifEmpty { "Waiting for playback..." }) }
        var artistName by remember { mutableStateOf(currentArtist.ifEmpty { "Spotify Broadcast Radar" }) }
        var trackLen by remember { mutableLongStateOf(currentLengthMs) }
        var trackPos by remember { mutableLongStateOf(currentPositionMs) }

        var savedStat by remember { mutableIntStateOf(countSaved) }
        var discardedStat by remember { mutableIntStateOf(countDiscarded) }
        var adsStat by remember { mutableIntStateOf(countAds) }

        var vaultFiles by remember { mutableStateOf(listOf<VaultTrack>()) }

        fun reloadVaultList() {
            val dir = getVaultDirectory(context)
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }
                ?.map { VaultTrack(it, it.name, it.length(), it.lastModified()) }
                ?.sortedByDescending { it.modifiedAt } ?: emptyList()
            vaultFiles = files
        }

        LaunchedEffect(Unit) {
            stateUpdater = { engineState = it }
            trackMetaUpdater = { t, a, l, p ->
                trackTitle = t
                artistName = a
                trackLen = l
                trackPos = p
            }
            statsUpdater = { s, d, ad ->
                savedStat = s
                discardedStat = d
                adsStat = ad
            }
            vaultRefreshTrigger = { reloadVaultList() }
            reloadVaultList()
        }

        fun disarmEngine() {
            if (isRecording) finalizeCurrentRecording()
            isArmed = false
            try {
                context.applicationContext.unregisterReceiver(spotifyReceiver)
            } catch (_: Exception) {}
            mediaProjection?.stop()
            mediaProjection = null
            bridge.stopForegroundTask()
            engineState = EngineState.DISARMED
            bridge.showToast("Spotify Recorder Disarmed")
        }

        fun armEngine() {
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
                                mediaProjection = mp

                                val filter = IntentFilter().apply {
                                    addAction("com.spotify.music.metadatachanged")
                                    addAction("com.spotify.music.playbackstatechanged")
                                    addAction("com.spotify.music.queuechanged")
                                    addAction("com.spotify.mobile.android.metadatachanged")
                                    addAction("com.spotify.mobile.android.playbackstatechanged")
                                    addAction("com.spotify.mobile.android.queuechanged")
                                }

                                val appContext = context.applicationContext
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    appContext.registerReceiver(spotifyReceiver, filter, Context.RECEIVER_EXPORTED)
                                } else {
                                    appContext.registerReceiver(spotifyReceiver, filter)
                                }

                                isArmed = true
                                engineState = EngineState.ARMED_LISTENING
                                bridge.showToast("Spotify Radar Armed! Play a song in Spotify.")
                            } else {
                                bridge.log("SPOTIFY_ERR", "MediaProjection dispatch returned null from foreground service.")
                                bridge.showToast("Failed to initialize MediaProjection.")
                            }
                        }
                    } else {
                        bridge.showToast("Media projection consent rejected.")
                    }
                }
            }
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

            // Main Radar & Arming Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, if (isArmed) Color(0xFF1DB954).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(engineState.color)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                engineState.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = engineState.color
                            )
                        }

                        Button(
                            onClick = { if (isArmed) disarmEngine() else armEngine() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isArmed) Color(0xFFDA3633) else Color(0xFF1DB954)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                if (isArmed) "DISARM" else "ARM ENGINE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Live Track Info
                    Text(trackTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    Text(artistName, color = Color(0xFF8B949E), fontSize = 12.sp, maxLines = 1)

                    Spacer(Modifier.height(10.dp))

                    // Duration Bar
                    val progress = if (trackLen > 0) (trackPos.toFloat() / trackLen.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF1DB954),
                        trackColor = Color(0xFF21262D)
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMs(trackPos), fontSize = 10.sp, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace)
                        Text(formatMs(trackLen), fontSize = 10.sp, color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace)
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
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.name.removeSuffix(".wav"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${track.sizeBytes / (1024 * 1024)} MB", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Text(SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(track.modifiedAt)), color = Color(0xFF8B949E), fontSize = 10.sp)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
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

    override fun onStop(context: Context) {
        if (isArmed) {
            try {
                context.applicationContext.unregisterReceiver(spotifyReceiver)
            } catch (_: Exception) {}
            isArmed = false
        }
        if (isRecording) {
            abortAndDiscard("Plugin container shutdown")
        }
        mediaProjection?.stop()
        mediaProjection = null
        activeBridge?.stopForegroundTask()
    }
}