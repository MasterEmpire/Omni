package com.omni.plugin.spotify

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.os.SystemClock
import kotlinx.coroutines.delay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import java.io.FileInputStream
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
    DISARMED("RADAR MONITORING (RECORDING DISARMED)", Color(0xFF8B949E)),
    ARMED_LISTENING("RADAR ARMED (READY TO CAPTURE 0:00)", Color(0xFF1DB954)),
    RECORDING("CAPTURING CLEAN STREAM", Color(0xFF58A6FF)),
    SKIPPING_AD("AD SHIELD ACTIVE: SKIPPING COMMERCIAL", Color(0xFFD29922)),
    WAITING_CLEAN_START("JOINED MID-TRACK (WAITING FOR NEXT 0:00)", Color(0xFFBC8CFF)),
    ALREADY_EXISTS("SONG ALREADY IN VAULT", Color(0xFF388BFD)),
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
    @Volatile private var isPlayingTrack = false
    @Volatile private var lastSyncTimestamp = 0L
    @Volatile private var wasInterrupted = false
    @Volatile private var tempRecordingFile: File? = null
    @Volatile private var recordedBytesCount = 0L

    // Frozen Track Identity for the active take (Prevents cross-track identity theft)
    @Volatile private var recordingTrackTitle = ""
    @Volatile private var recordingArtistName = ""
    @Volatile private var recordingExpectedDurationMs = 0L

    // UI Reactive State Bridges
    private var stateUpdater: ((EngineState) -> Unit)? = null
    private var trackMetaUpdater: ((title: String, artist: String, lengthMs: Long, posMs: Long, isPlaying: Boolean) -> Unit)? = null
    private var statsUpdater: ((saved: Int, discarded: Int, ads: Int) -> Unit)? = null
    private var vaultRefreshTrigger: (() -> Unit)? = null

    private var countSaved = 0
    private var countDiscarded = 0
    private var countAds = 0
    private val recordLock = Any()

    private val spotifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val extrasSummary = intent.extras?.let { bundle ->
                bundle.keySet().joinToString(", ") { key ->
                    "$key=${bundle.get(key)}"
                }
            } ?: "no extras"
            activeBridge?.log("SPOTIFY_RX", "📥 [$action] -> $extrasSummary")

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
        val publicVault = File(musicDir, "Omni Spotify")
        if (!publicVault.exists()) publicVault.mkdirs()
        if (publicVault.canWrite()) return publicVault

        // Scoped Storage fallback: Guaranteed accessible on Android 10+
        val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val appVault = File(appMusic ?: context.filesDir, "Omni Spotify").apply { if (!exists()) mkdirs() }
        return appVault
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
        isPlayingTrack = isPlaying
        lastSyncTimestamp = SystemClock.elapsedRealtime()
        trackMetaUpdater?.invoke(newTrack, newArtist, newLengthMs, newPos, isPlaying)

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

        // 3. Duplicate Vault Check (.m4a and .wav)
        val ctx = activeContext ?: return
        val vaultDir = getVaultDirectory(ctx)
        val baseName = "${sanitizeFilename(newArtist)} - ${sanitizeFilename(newTrack)}"
        val m4aFile = File(vaultDir, "$baseName.m4a")
        val wavFile = File(vaultDir, "$baseName.wav")
        if ((m4aFile.exists() && m4aFile.length() > 1000) || (wavFile.exists() && wavFile.length() > 44)) {
            stateUpdater?.invoke(EngineState.ALREADY_EXISTS)
            activeBridge?.log("SPOTIFY_RADAR", "Track already in vault: $baseName")
            return
        }

        // 4. Clean Start Policy (Must begin from the very first second: <= 1200ms)
        if (newPos > 1200L) {
            stateUpdater?.invoke(EngineState.WAITING_CLEAN_START)
            activeBridge?.log("SPOTIFY_RADAR", "Mid-track start (${newPos}ms). Waiting for next clean 0:00 track.")
            return
        }

        // 5. Conditions met: Launch Audio Stream Capture if armed
        if (isArmed && isPlaying && mediaProjection != null) {
            startAudioRecording(vaultDir, targetName, newLengthMs)
        } else if (!isArmed) {
            stateUpdater?.invoke(EngineState.DISARMED)
        }
    }

    private fun handlePlaybackStateChanged(intent: Intent) {
        val isPlaying = intent.getBooleanExtra("playing", false)
        val pos = intent.getIntExtra("playbackPosition", -1).toLong()

        val fallbackTrack = intent.getStringExtra("track") ?: intent.getStringExtra("title")
        val fallbackArtist = intent.getStringExtra("artist")
        if (!fallbackTrack.isNullOrEmpty()) currentTrackTitle = fallbackTrack
        if (!fallbackArtist.isNullOrEmpty()) currentArtist = fallbackArtist

        isPlayingTrack = isPlaying
        lastSyncTimestamp = SystemClock.elapsedRealtime()

        if (pos >= 0) {
            currentPositionMs = pos
        }
        trackMetaUpdater?.invoke(currentTrackTitle, currentArtist, currentLengthMs, currentPositionMs, isPlaying)
        activeBridge?.log("SPOTIFY_RADAR", "State update: playing=$isPlaying, pos=${currentPositionMs}ms, track='$currentTrackTitle'")

        if (isRecording) {
            val recordedMs = (recordedBytesCount * 1000L) / (44100 * 2 * 2)
            if (!isPlaying) {
                // Natural end-of-track check: If within 4 seconds of completion, commit rather than discard
                if (currentLengthMs > 0 && recordedMs >= currentLengthMs - 4000L) {
                    activeBridge?.log("SPOTIFY_RADAR", "Track paused at natural end (${recordedMs}ms/${currentLengthMs}ms). Finalizing take.")
                    finalizeCurrentRecording()
                } else {
                    activeBridge?.log("SPOTIFY_RADAR", "Playback paused early (${recordedMs}ms of ${currentLengthMs}ms). Discarding.")
                    abortAndDiscard("Paused early")
                }
            } else if (pos >= 0 && currentLengthMs > 0) {
                // If playhead resets to 0:00 while we captured the full song, that is the natural track hand-off!
                if (pos <= 2000L && recordedMs >= currentLengthMs - 4000L) {
                    activeBridge?.log("SPOTIFY_RADAR", "Playhead reset to 0:00 after full play (${recordedMs}ms). Finalizing take.")
                    finalizeCurrentRecording()
                }
            }
        }
    }

    private fun startAudioRecording(vaultDir: File, targetFilename: String, expectedDurationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) return

        // Freeze active track identity for this recording session
        recordingTrackTitle = currentTrackTitle
        recordingArtistName = currentArtist
        recordingExpectedDurationMs = expectedDurationMs

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

                            // Auto-Commit Guard: Once the full song has played (+1.2s buffer),
                            // finalize immediately to prevent post-song commercial ads from corrupting the take!
                            val recMs = (recordedBytesCount * 1000L) / (44100 * 2 * 2)
                            if (expectedDurationMs > 0 && recMs >= expectedDurationMs + 1200L) {
                                activeBridge?.log("SPOTIFY_RECORDER", "🎯 Track duration reached (${recMs}ms >= ${expectedDurationMs}ms). Auto-committing take before ad starts!")
                                finalizeCurrentRecording()
                                stateUpdater?.invoke(EngineState.SKIPPING_AD)
                                trackMetaUpdater?.invoke("Commercial / Intermission", "Audio Capture Muted (Waiting for next song)", 0L, 0L, false)
                                break
                            }
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
        synchronized(recordLock) {
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
            val targetLength = if (recordingExpectedDurationMs > 0) recordingExpectedDurationMs else currentLengthMs

            // Tolerance window: accommodates Spotify's 1-4s lead-out variance
            val isDurationComplete = targetLength > 0 && (
                abs(recordedDurationMs - targetLength) <= 5000L ||
                recordedDurationMs >= targetLength - 4000L
            )

            if (!wasInterrupted && isDurationComplete) {
                val ctx = activeContext ?: return
                val vaultDir = getVaultDirectory(ctx)
                val saveArtist = recordingArtistName.ifEmpty { currentArtist }
                val saveTitle = recordingTrackTitle.ifEmpty { currentTrackTitle }
                val cleanArtist = sanitizeFilename(saveArtist)
                val cleanTitle = sanitizeFilename(saveTitle)

                val finalM4a = File(vaultDir, "$cleanArtist - $cleanTitle.m4a")
                activeBridge?.log("SPOTIFY_VAULT", "⚡ Compressing & encoding to AAC (.m4a): ${finalM4a.name}...")

                val aacSuccess = encodePcmToAac(temp, finalM4a, 44100, 2, 192000)
                val savedOk: Boolean
                val finalFile: File

                if (aacSuccess && finalM4a.exists() && finalM4a.length() > 1000) {
                    temp.delete()
                    savedOk = true
                    finalFile = finalM4a
                } else {
                    activeBridge?.log("SPOTIFY_WARN", "AAC encoder fallback triggered. Preserving WAV...")
                    writeWavHeader(temp, 44100, 2, 16)
                    val finalWav = File(vaultDir, "$cleanArtist - $cleanTitle.wav")
                    savedOk = if (temp.renameTo(finalWav)) true else {
                        try { temp.copyTo(finalWav, overwrite = true); temp.delete(); true } catch (_: Exception) { false }
                    }
                    finalFile = finalWav
                }

                if (savedOk) {
                    countSaved++
                    statsUpdater?.invoke(countSaved, countDiscarded, countAds)
                    vaultRefreshTrigger?.invoke()
                    val mbSize = String.format(Locale.US, "%.1f", finalFile.length() / (1024.0 * 1024.0))
                    activeBridge?.log("SPOTIFY_VAULT", "✅ [SAVED TO VAULT] ${finalFile.name} (${mbSize} MB, Recorded: ${recordedDurationMs}ms / Expected: ${targetLength}ms)")
                    activeBridge?.showToast("Saved: ${finalFile.name} (${mbSize} MB)")
                } else {
                    activeBridge?.log("SPOTIFY_ERR", "Failed to commit ${finalFile.name} to storage.")
                }
            } else {
                temp.delete()
                countDiscarded++
                statsUpdater?.invoke(countSaved, countDiscarded, countAds)
                activeBridge?.log("SPOTIFY_RECORDER", "❌ Discarded take (Recorded: ${recordedDurationMs}ms vs Expected: ${targetLength}ms, Interrupted: $wasInterrupted)")
            }

            tempRecordingFile = null
            recordedBytesCount = 0L
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
        var anchorPos by remember { mutableLongStateOf(currentPositionMs) }
        var isPlayingNow by remember { mutableStateOf(isPlayingTrack) }
        var currentDisplayPos by remember { mutableLongStateOf(currentPositionMs) }

        var savedStat by remember { mutableIntStateOf(countSaved) }
        var discardedStat by remember { mutableIntStateOf(countDiscarded) }
        var adsStat by remember { mutableIntStateOf(countAds) }

        var vaultFiles by remember { mutableStateOf(listOf<VaultTrack>()) }

        fun reloadVaultList() {
            val dir = getVaultDirectory(context)
            val files = dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".m4a") || it.name.endsWith(".wav")) }
                ?.map { VaultTrack(it, it.name, it.length(), it.lastModified()) }
                ?.sortedByDescending { it.modifiedAt } ?: emptyList()
            vaultFiles = files
            activeBridge?.log("SPOTIFY_VAULT", "Vault refreshed: ${files.size} track(s) discovered in ${dir.absolutePath}")
        }

        // Real-Time Monotonic Position Interpolator
        LaunchedEffect(isPlayingNow, anchorPos, trackLen) {
            if (isPlayingNow && trackLen > 0) {
                val baseTime = SystemClock.elapsedRealtime()
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - baseTime
                    val computed = anchorPos + elapsed
                    currentDisplayPos = if (computed >= trackLen - 1000L) trackLen else computed.coerceAtMost(trackLen)
                    delay(120)
                }
            } else {
                currentDisplayPos = anchorPos.coerceAtMost(trackLen)
            }
        }

        // Mount Passive Spotify Broadcast Listener by default
        LaunchedEffect(Unit) {
            stateUpdater = { engineState = it }
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
            vaultRefreshTrigger = { reloadVaultList() }

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
                activeBridge?.log("SPOTIFY_RADAR", "Passive broadcast listener mounted successfully.")
            } catch (e: Exception) {
                activeBridge?.log("SPOTIFY_ERR", "Error mounting receiver: ${e.message}")
            }

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

                                isArmed = true
                                engineState = EngineState.ARMED_LISTENING
                                bridge.showToast("Spotify Radar Armed! Ready to capture next 0:00 track.")
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
            val isAdShieldActive = engineState == EngineState.SKIPPING_AD
            val cardBorderColor = when {
                isAdShieldActive -> Color(0xFFD29922)
                isArmed -> Color(0xFF1DB954).copy(alpha = 0.8f)
                else -> Color.White.copy(alpha = 0.08f)
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(if (isAdShieldActive) 2.dp else 1.dp, cardBorderColor),
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
                                fontSize = 10.sp,
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
                        if (isAdShieldActive) "Commercial Advertisement" else trackTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    Text(
                        if (isAdShieldActive) "Ad stream blocked — Waiting for next song" else artistName,
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
                                    Text(track.name.removeSuffix(".m4a").removeSuffix(".wav"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val mbStr = String.format(Locale.US, "%.1f MB", track.sizeBytes / (1024.0 * 1024.0))
                                        Text(mbStr, color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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