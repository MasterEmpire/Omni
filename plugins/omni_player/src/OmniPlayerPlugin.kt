package com.omni.plugin.player

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Rational
import android.util.Size
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap



// ==========================================
// MEDIA STORE QUERY & CACHE ENGINE
// ==========================================

data class VideoFolder(
    val id: String,
    val name: String,
    val path: String,
    val videoCount: Int,
    val totalSize: Long,
    val firstVideoUri: Uri? = null
)

data class VideoItem(
    val id: Long,
    val name: String,
    val path: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val folderName: String
)

object VideoRepository {
    private val thumbnailCache = ConcurrentHashMap<Long, Bitmap>()

    suspend fun getFolders(context: Context): List<VideoFolder> = withContext(Dispatchers.IO) {
        val videos = getAllVideos(context)
        val grouped = videos.groupBy { it.folderName }
        grouped.map { (folderName, items) ->
            val first = items.firstOrNull()
            val folderPath = first?.path?.substringBeforeLast("/", "") ?: ""
            VideoFolder(
                id = folderName,
                name = folderName,
                path = folderPath,
                videoCount = items.size,
                totalSize = items.sumOf { it.sizeBytes },
                firstVideoUri = first?.uri
            )
        }.sortedByDescending { it.videoCount }
    }

    suspend fun getVideosInFolder(context: Context, folderName: String): List<VideoItem> = withContext(Dispatchers.IO) {
        getAllVideos(context).filter { it.folderName.equals(folderName, ignoreCase = true) }
    }

    suspend fun getAllVideos(context: Context): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        try {
            val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                queryUri,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video_$id"
                    val path = cursor.getString(dataCol) ?: ""
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val bucket = cursor.getString(bucketCol) ?: "Internal"
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    list.add(
                        VideoItem(
                            id = id,
                            name = name,
                            path = path,
                            uri = contentUri,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModified = date,
                            folderName = bucket
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        list
    }

    suspend fun getThumbnail(context: Context, videoId: Long, uri: Uri, path: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache[videoId]?.let { return@withContext it }

        val bmp = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(512, 384), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    videoId,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                ) ?: if (path.isNotEmpty()) {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
                } else null
            }
        } catch (_: Exception) {
            null
        }

        if (bmp != null) {
            thumbnailCache[videoId] = bmp
        }
        bmp
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var na = 0L
                while (i < a.length && a[i].isDigit()) {
                    na = (na * 10L + (a[i] - '0')).coerceAtMost(Long.MAX_VALUE / 20L)
                    i++
                }
                var nb = 0L
                while (j < b.length && b[j].isDigit()) {
                    nb = (nb * 10L + (b[j] - '0')).coerceAtMost(Long.MAX_VALUE / 20L)
                    j++
                }
                if (na != nb) return na.compareTo(nb)
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }
}

object VideoSnapshotHelper {

    suspend fun grabVideoFrame(
        context: Context,
        video: VideoItem,
        positionMs: Long,
        hostActivity: Activity?,
        playerView: PlayerView?
    ): Bitmap? {
        val retrieved = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                try {
                    retriever.setDataSource(context, video.uri)
                } catch (_: Exception) {
                    if (video.path.isNotEmpty()) {
                        retriever.setDataSource(video.path)
                    } else {
                        return@withContext null
                    }
                }
                val timeUs = (positionMs.coerceAtLeast(0L)) * 1000L
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        if (retrieved != null) return retrieved

        val window = hostActivity?.window ?: return null
        val pv = playerView ?: return null
        if (pv.width <= 0 || pv.height <= 0) return null

        return suspendCancellableCoroutine { cont ->
            try {
                val loc = IntArray(2)
                pv.getLocationInWindow(loc)
                val rect = Rect(loc[0], loc[1], loc[0] + pv.width, loc[1] + pv.height)
                val decor = window.decorView
                if (!rect.intersect(0, 0, decor.width, decor.height) || rect.width() <= 0 || rect.height() <= 0) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val bmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, rect, bmp, { result ->
                    if (cont.isActive) {
                        if (result == PixelCopy.SUCCESS) {
                            cont.resume(bmp)
                        } else {
                            bmp.recycle()
                            cont.resume(null)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun burnSubtitleAndSaveJpeg(
        context: Context,
        rawFrame: Bitmap,
        videoName: String,
        subtitleText: String?,
        fontSizeSp: Float,
        fontColorArgb: Int,
        boxColorArgb: Int,
        hasOutline: Boolean,
        yFraction: Float
    ): String? {
        val workingBitmap = if (rawFrame.config == Bitmap.Config.ARGB_8888 && rawFrame.isMutable) {
            rawFrame
        } else {
            val copy = rawFrame.copy(Bitmap.Config.ARGB_8888, true)
            rawFrame.recycle()
            copy
        }

        try {
            if (!subtitleText.isNullOrBlank()) {
                val canvas = AndroidCanvas(workingBitmap)
                val bmpW = workingBitmap.width.toFloat()
                val bmpH = workingBitmap.height.toFloat()
                val refDim = minOf(bmpW, bmpH)
                val textSizePx = (refDim * (fontSizeSp / 450f)).coerceIn(20f, bmpH * 0.12f)
                val maxTextWidth = (bmpW * 0.88f).toInt().coerceAtLeast(100)

                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fontColorArgb
                    textSize = textSizePx
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(textSizePx * 0.12f, 0f, textSizePx * 0.05f, AndroidColor.BLACK)
                }

                val layout = StaticLayout.Builder
                    .obtain(subtitleText, 0, subtitleText.length, textPaint, maxTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.15f)
                    .setIncludePad(false)
                    .build()

                var maxLineWidth = 0f
                for (i in 0 until layout.lineCount) {
                    val lw = layout.getLineWidth(i)
                    if (lw > maxLineWidth) maxLineWidth = lw
                }

                val padX = textSizePx * 0.45f
                val padY = textSizePx * 0.25f
                val boxBottom = (bmpH * (1f - yFraction.coerceIn(0.04f, 0.85f))).coerceIn(layout.height + padY * 2f, bmpH - 8f)
                val boxTop = boxBottom - layout.height - padY * 2f
                val boxLeft = (bmpW - maxLineWidth) / 2f - padX
                val boxRight = (bmpW + maxLineWidth) / 2f + padX

                if (AndroidColor.alpha(boxColorArgb) > 0) {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = boxColorArgb
                        style = Paint.Style.FILL
                    }
                    val radius = textSizePx * 0.18f
                    canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), radius, radius, bgPaint)
                }

                val textLeft = (bmpW - maxTextWidth) / 2f
                val textTop = boxTop + padY
                canvas.save()
                canvas.translate(textLeft, textTop)

                if (hasOutline || AndroidColor.alpha(boxColorArgb) == 0) {
                    val strokePaint = TextPaint(textPaint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (textSizePx * 0.08f).coerceAtLeast(2f)
                        color = AndroidColor.BLACK
                        clearShadowLayer()
                    }
                    val strokeLayout = StaticLayout.Builder
                        .obtain(subtitleText, 0, subtitleText.length, strokePaint, maxTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.15f)
                        .setIncludePad(false)
                        .build()
                    strokeLayout.draw(canvas)
                }

                layout.draw(canvas)
                canvas.restore()
            }

            val cleanTitle = videoName.substringBeforeLast(".")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(28)
                .ifEmpty { "Video" }
            val filename = "OmniShot_${cleanTitle}_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OmniSnapshots")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val ok = resolver.openOutputStream(uri)?.use { out ->
                    workingBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return if (ok) filename else null
            } else {
                val picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val outDir = File(picsDir, "OmniSnapshots").apply { if (!exists()) mkdirs() }
                val outFile = File(outDir, filename)
                val ok = FileOutputStream(outFile).use { out ->
                    workingBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (ok) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(outFile.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )
                    return filename
                }
                return null
            }
        } catch (_: Exception) {
            return null
        } finally {
            workingBitmap.recycle()
        }
    }
}

// ==========================================
// PRIMARY OMNI PLAYER ENTRY POINT
// ==========================================

class OmniPlayerPlugin : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF07090E),
                        surface = Color(0xFF11151D),
                        primary = Color(0xFF00E676)
                    )
                ) {
                    PlayerMainScreen(context, bridge)
                }
            }
        }
    }

    @Composable
    fun PlayerMainScreen(context: Context, bridge: HostBridge) {
        var currentFolder by remember { mutableStateOf<VideoFolder?>(null) }
        var currentPlayingVideo by remember { mutableStateOf<VideoItem?>(null) }
        var videoQueue by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
        var isPlayerFullScreen by remember { mutableStateOf(false) }
        var isBackgroundAudioEnabled by remember { mutableStateOf(false) }
        val playerPrefs = remember(context) {
            context.getSharedPreferences("omni_player_prefs", Context.MODE_PRIVATE)
        }
        var isAutoPlayNextEnabled by remember {
            mutableStateOf(playerPrefs.getBoolean("key_auto_play_next", true))
        }

        val player = remember(context) {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        val subtitleState = remember {
            SubtitleState().apply { loadStylePrefs(context) }
        }
        var isPlaying by remember { mutableStateOf(false) }
        var currentPos by remember { mutableLongStateOf(0L) }
        var totalDuration by remember { mutableLongStateOf(1L) }
        var libraryRefreshTick by remember { mutableIntStateOf(0) }

        fun saveCurrentVideoState(vid: VideoItem?) {
            if (vid == null) return
            PlaybackHistoryManager.saveVideoState(
                context = context,
                videoId = vid.id.toString(),
                positionMs = player.currentPosition,
                subtitleName = subtitleState.currentSubtitleName,
                rawSrtText = subtitleState.rawSrtContent,
                delaySec = subtitleState.delaySec,
                yFraction = subtitleState.yFraction,
                isEnabled = subtitleState.isEnabled
            )
        }

        fun stopAndClearPlayback() {
            saveCurrentVideoState(currentPlayingVideo)
            player.pause()
            player.stop()
            player.clearMediaItems()
            isPlaying = false
            isBackgroundAudioEnabled = false
            isPlayerFullScreen = false
            bridge.stopMediaPlayback()
            currentPlayingVideo = null
        }

        fun toggleBackgroundAudio() {
            val vid = currentPlayingVideo ?: return
            if (!isBackgroundAudioEnabled) {
                isBackgroundAudioEnabled = true
                bridge.startMediaPlayback(vid.name, "Omni Background Player", player.isPlaying) { playState ->
                    if (playState) player.play() else player.pause()
                }
                bridge.showToast("Background audio enabled")
            } else {
                isBackgroundAudioEnabled = false
                bridge.stopMediaPlayback()
                bridge.showToast("Background audio disabled")
            }
        }

        // Load media only when the active video changes
        LaunchedEffect(currentPlayingVideo?.id) {
            val vid = currentPlayingVideo ?: return@LaunchedEffect
            subtitleState.clearSubtitle()
            val savedState = PlaybackHistoryManager.getSavedState(context, vid.id.toString())
            if (savedState != null) {
                subtitleState.delaySec = savedState.optDouble("delaySec", 0.0).toFloat()
                subtitleState.yFraction = savedState.optDouble("yFraction", 0.08).toFloat()
                subtitleState.isEnabled = savedState.optBoolean("isEnabled", true)
            }
            val cachedSub = PlaybackHistoryManager.getSavedSubtitle(context, vid.id.toString())
            if (cachedSub != null) {
                subtitleState.loadSrtText(cachedSub.second, cachedSub.first)
            } else if (vid.path.isNotEmpty()) {
                val sidecar = withContext(Dispatchers.IO) {
                    try {
                        val videoFile = File(vid.path)
                        val parentDir = videoFile.parentFile
                        val baseName = videoFile.nameWithoutExtension
                        val match = parentDir?.listFiles()?.firstOrNull { f ->
                            f.isFile &&
                                (f.extension.equals("srt", true) || f.extension.equals("vtt", true)) &&
                                f.nameWithoutExtension.equals(baseName, ignoreCase = true)
                        }
                        if (match != null && match.length() > 0) {
                            Pair(match.name, match.readText(Charsets.UTF_8))
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (sidecar != null) {
                    subtitleState.loadSrtText(sidecar.second, sidecar.first)
                    PlaybackHistoryManager.saveVideoState(
                        context = context,
                        videoId = vid.id.toString(),
                        positionMs = savedState?.optLong("positionMs", 0L) ?: 0L,
                        subtitleName = sidecar.first,
                        rawSrtText = sidecar.second,
                        delaySec = subtitleState.delaySec,
                        yFraction = subtitleState.yFraction,
                        isEnabled = subtitleState.isEnabled
                    )
                    bridge.showToast("Auto-loaded: ${sidecar.first}")
                }
            }

            totalDuration = vid.durationMs.coerceAtLeast(1L)
            val resumeMs = savedState?.optLong("positionMs", 0L) ?: 0L
            currentPos = resumeMs
            player.setMediaItem(MediaItem.fromUri(vid.uri), resumeMs)
            player.prepare()
            player.play()
            isPlaying = true

            if (isBackgroundAudioEnabled) {
                bridge.updateMediaPlayback(vid.name, "Omni Background Player", true)
            }
        }

        // Track Timeline Progress across fullscreen & background mini-player
        LaunchedEffect(player, currentPlayingVideo?.id, isPlaying) {
            while (isActive && currentPlayingVideo != null) {
                if (player.isPlaying) {
                    currentPos = player.currentPosition
                    if (player.duration > 0) totalDuration = player.duration
                }
                delay(200)
            }
        }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (isBackgroundAudioEnabled) {
                        bridge.updateMediaPlayback(
                            currentPlayingVideo?.name ?: "Omni Player",
                            "Omni Background Player",
                            playing
                        )
                    }
                    if (!playing) {
                        saveCurrentVideoState(currentPlayingVideo)
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.duration > 0) {
                        totalDuration = player.duration
                    } else if (playbackState == Player.STATE_ENDED) {
                        val finishedVid = currentPlayingVideo
                        if (finishedVid != null) {
                            PlaybackHistoryManager.saveVideoState(
                                context = context,
                                videoId = finishedVid.id.toString(),
                                positionMs = 0L,
                                subtitleName = subtitleState.currentSubtitleName,
                                rawSrtText = subtitleState.rawSrtContent,
                                delaySec = subtitleState.delaySec,
                                yFraction = subtitleState.yFraction,
                                isEnabled = subtitleState.isEnabled
                            )
                            libraryRefreshTick++
                            if (isAutoPlayNextEnabled) {
                                val curIdx = videoQueue.indexOfFirst { it.id == finishedVid.id }
                                if (curIdx >= 0 && curIdx < videoQueue.lastIndex) {
                                    val nextVid = videoQueue[curIdx + 1]
                                    currentPlayingVideo = nextVid
                                    bridge.showToast("Up Next: ${nextVid.name}")
                                }
                            }
                        }
                    }
                }
            }
            player.addListener(listener)
            onDispose {
                saveCurrentVideoState(currentPlayingVideo)
                bridge.stopMediaPlayback()
                bridge.setImmersiveMode(false)
                bridge.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                player.removeListener(listener)
                player.release()
            }
        }

        if (isPlayerFullScreen && currentPlayingVideo != null) {
            XPlayerCinemaScreen(
                context = context,
                bridge = bridge,
                player = player,
                video = currentPlayingVideo!!,
                queue = videoQueue,
                isPlaying = isPlaying,
                currentPos = currentPos,
                totalDuration = totalDuration,
                subtitleState = subtitleState,
                isBackgroundAudioEnabled = isBackgroundAudioEnabled,
                isAutoPlayNextEnabled = isAutoPlayNextEnabled,
                onToggleAutoPlayNext = { enabled ->
                    isAutoPlayNextEnabled = enabled
                    playerPrefs.edit().putBoolean("key_auto_play_next", enabled).apply()
                },
                onToggleBackgroundAudio = { toggleBackgroundAudio() },
                onSeekTo = { targetMs ->
                    val clamped = targetMs.coerceIn(0L, totalDuration.coerceAtLeast(1L))
                    currentPos = clamped
                    player.seekTo(clamped)
                },
                onClosePlayer = {
                    bridge.setImmersiveMode(false)
                    bridge.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                    if (isBackgroundAudioEnabled) {
                        saveCurrentVideoState(currentPlayingVideo)
                        isPlayerFullScreen = false
                    } else {
                        stopAndClearPlayback()
                    }
                    libraryRefreshTick++
                },
                onSelectVideo = { nextVideo ->
                    saveCurrentVideoState(currentPlayingVideo)
                    currentPlayingVideo = nextVideo
                    libraryRefreshTick++
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF07090E))
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (currentFolder != null) {
                        FolderVideosScreen(
                            context = context,
                            folder = currentFolder!!,
                            refreshKey = libraryRefreshTick,
                            onBack = { currentFolder = null },
                            onPlayVideo = { video, queue ->
                                videoQueue = queue
                                if (currentPlayingVideo?.id != video.id) {
                                    saveCurrentVideoState(currentPlayingVideo)
                                    currentPlayingVideo = video
                                }
                                isPlayerFullScreen = true
                            }
                        )
                    } else {
                        FolderCatalogScreen(
                            context = context,
                            bridge = bridge,
                            refreshKey = libraryRefreshTick,
                            onOpenFolder = { folder -> currentFolder = folder },
                            onPlayRecent = { video, queue ->
                                videoQueue = queue
                                if (currentPlayingVideo?.id != video.id) {
                                    saveCurrentVideoState(currentPlayingVideo)
                                    currentPlayingVideo = video
                                }
                                isPlayerFullScreen = true
                            }
                        )
                    }
                }

                if (currentPlayingVideo != null) {
                    BackgroundMiniPlayerBar(
                        context = context,
                        video = currentPlayingVideo!!,
                        isPlaying = isPlaying,
                        currentPos = currentPos,
                        totalDuration = totalDuration,
                        onOpenPlayer = { isPlayerFullScreen = true },
                        onTogglePlayPause = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        onClose = { stopAndClearPlayback() }
                    )
                }
            }
        }
    }

    @Composable
    fun BackgroundMiniPlayerBar(
        context: Context,
        video: VideoItem,
        isPlaying: Boolean,
        currentPos: Long,
        totalDuration: Long,
        onOpenPlayer: () -> Unit,
        onTogglePlayPause: () -> Unit,
        onClose: () -> Unit
    ) {
        var thumbnail by remember(video.id) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(video.id) {
            thumbnail = VideoRepository.getThumbnail(context, video.id, video.uri, video.path)
        }

        val progressFraction = if (totalDuration > 0L) {
            (currentPos.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Surface(
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPlayer() }
        ) {
            Column {
                // Electric green progress hairline
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(Color(0xFF00E676))
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Stop Background Audio",
                            tint = Color(0xFF8B949E),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail!!.asImageBitmap(),
                                contentDescription = video.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CineHeadphonesIcon(tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${VideoRepository.formatDuration(currentPos)} / ${VideoRepository.formatDuration(totalDuration)}",
                            color = Color(0xFF8B949E),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF00E676).copy(alpha = 0.16f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CineHeadphonesIcon(tint = Color(0xFF00E676), modifier = Modifier.size(14.dp))
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(38.dp)
                    ) {
                        CinePlayPauseIcon(
                            isPlaying = isPlaying,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 1: DEVICE FOLDERS VIEW
    // ==========================================

    @Composable
    fun FolderCatalogScreen(
        context: Context,
        bridge: HostBridge,
        refreshKey: Int = 0,
        onOpenFolder: (VideoFolder) -> Unit,
        onPlayRecent: (VideoItem, List<VideoItem>) -> Unit = { _, _ -> }
    ) {
        var folders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
        var allVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
        var historyMap by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
        var isLoading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        fun loadData(showSpinner: Boolean = true) {
            scope.launch {
                if (showSpinner) isLoading = true
                val loadedVideos = VideoRepository.getAllVideos(context)
                val loadedFolders = withContext(Dispatchers.IO) {
                    loadedVideos.groupBy { it.folderName }.map { (folderName, items) ->
                        val first = items.firstOrNull()
                        VideoFolder(
                            id = folderName,
                            name = folderName,
                            path = first?.path?.substringBeforeLast("/", "") ?: "",
                            videoCount = items.size,
                            totalSize = items.sumOf { it.sizeBytes },
                            firstVideoUri = first?.uri
                        )
                    }.sortedByDescending { it.videoCount }
                }
                allVideos = loadedVideos
                folders = loadedFolders
                historyMap = PlaybackHistoryManager.getAllHistory(context)
                isLoading = false
            }
        }

        LaunchedEffect(refreshKey) {
            loadData(showSpinner = folders.isEmpty())
        }

        val recentVideos = remember(allVideos, historyMap) {
            allVideos.mapNotNull { vid ->
                val obj = historyMap[vid.id.toString()] ?: return@mapNotNull null
                val pos = obj.optLong("positionMs", 0L)
                val updated = obj.optLong("updatedAt", 0L)
                if (pos > 2000L) Triple(vid, pos, updated) else null
            }.sortedByDescending { it.third }.take(10)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07090E))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CineFolderIcon(tint = Color(0xFF00E676), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Omni Cinema",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "LOCAL MEDIA LIBRARY",
                            color = Color(0xFF8B949E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Row {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF58A6FF))
                    }
                    IconButton(onClick = { bridge.close() }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color(0xFF8B949E))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp)
                }
            } else if (folders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CineFolderIcon(tint = Color(0xFF484F58), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No video folders detected", color = Color(0xFF8B949E), fontSize = 14.sp)
                        Text("Verify device storage permissions in settings.", color = Color(0xFF484F58), fontSize = 11.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (recentVideos.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(
                                    text = "CONTINUE WATCHING",
                                    color = Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(recentVideos, key = { it.first.id }) { (vid, savedPos, _) ->
                                        var thumb by remember(vid.id) { mutableStateOf<Bitmap?>(null) }
                                        LaunchedEffect(vid.id) {
                                            thumb = VideoRepository.getThumbnail(context, vid.id, vid.uri, vid.path)
                                        }
                                        val frac = if (vid.durationMs > 0L) {
                                            (savedPos.toFloat() / vid.durationMs.toFloat()).coerceIn(0.02f, 1f)
                                        } else 0f

                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier
                                                .width(156.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    val folderQueue = allVideos.filter { it.folderName.equals(vid.folderName, true) }
                                                    onPlayRecent(vid, folderQueue.ifEmpty { listOf(vid) })
                                                }
                                        ) {
                                            Column {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(16f / 9f)
                                                        .background(Color.Black),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (thumb != null) {
                                                        Image(
                                                            bitmap = thumb!!.asImageBitmap(),
                                                            contentDescription = vid.name,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        CinePlayPauseIcon(isPlaying = false, tint = Color(0xFF8B949E), modifier = Modifier.size(18.dp))
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = Color.Black.copy(alpha = 0.8f),
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(end = 5.dp, bottom = 6.dp)
                                                    ) {
                                                        Text(
                                                            text = VideoRepository.formatDuration(vid.durationMs),
                                                            color = Color.White,
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomStart)
                                                            .fillMaxWidth()
                                                            .height(3.dp)
                                                            .background(Color.White.copy(alpha = 0.2f))
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxHeight()
                                                                .fillMaxWidth(frac)
                                                                .background(Color(0xFF00E676))
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = vid.name,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(folders) { folder ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenFolder(folder) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF181F2A),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CineFolderIcon(tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
                                    }
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${folder.videoCount} videos  •  ${VideoRepository.formatFileSize(folder.totalSize)}",
                                        color = Color(0xFF8B949E),
                                        fontSize = 12.sp
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF00E676).copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = "${folder.videoCount}",
                                        color = Color(0xFF00E676),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 2: FOLDER VIDEO GRID VIEW
    // ==========================================

    @Composable
    fun FolderVideosScreen(
        context: Context,
        folder: VideoFolder,
        refreshKey: Int = 0,
        onBack: () -> Unit,
        onPlayVideo: (VideoItem, List<VideoItem>) -> Unit
    ) {
        val prefs = remember(context) {
            context.getSharedPreferences("omni_player_prefs", Context.MODE_PRIVATE)
        }
        var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
        var historyMap by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
        var isLoading by remember { mutableStateOf(true) }
        var isSearchOpen by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var sortMode by remember { mutableStateOf(prefs.getString("key_folder_sort_mode", "NAME") ?: "NAME") }
        var showSortMenu by remember { mutableStateOf(false) }

        BackHandler {
            if (isSearchOpen) {
                isSearchOpen = false
                searchQuery = ""
            } else {
                onBack()
            }
        }

        LaunchedEffect(folder.name, refreshKey) {
            if (videos.isEmpty()) isLoading = true
            videos = VideoRepository.getVideosInFolder(context, folder.name)
            historyMap = PlaybackHistoryManager.getAllHistory(context)
            isLoading = false
        }

        val displayedVideos = remember(videos, searchQuery, sortMode) {
            val filtered = if (searchQuery.isBlank()) videos else {
                videos.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
            }
            when (sortMode) {
                "DATE" -> filtered.sortedByDescending { it.dateModified }
                "SIZE" -> filtered.sortedByDescending { it.sizeBytes }
                else -> filtered.sortedWith { a, b -> VideoRepository.naturalCompare(a.name, b.name) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07090E))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            folder.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("${displayedVideos.size} videos available", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        isSearchOpen = !isSearchOpen
                        if (!isSearchOpen) searchQuery = ""
                    }) {
                        Icon(
                            if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isSearchOpen) Color(0xFF00E676) else Color.White
                        )
                    }

                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showSortMenu = true }
                        ) {
                            Text(
                                text = when (sortMode) {
                                    "DATE" -> "Newest"
                                    "SIZE" -> "Size"
                                    else -> "A-Z"
                                },
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(Color(0xFF161B22))
                        ) {
                            listOf("NAME" to "Name (A-Z / Ep)", "DATE" to "Newest First", "SIZE" to "Largest Size").forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (sortMode == key) Color(0xFF00E676) else Color.White,
                                            fontWeight = if (sortMode == key) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        sortMode = key
                                        prefs.edit().putString("key_folder_sort_mode", key).apply()
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (isSearchOpen) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter videos in ${folder.name}...", color = Color(0xFF8B949E), fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(14.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedVideos, key = { it.id }) { video ->
                        val savedPos = historyMap[video.id.toString()]?.optLong("positionMs", 0L) ?: 0L
                        val progressFrac = if (savedPos > 2000L && video.durationMs > 0L) {
                            (savedPos.toFloat() / video.durationMs.toFloat()).coerceIn(0.02f, 1f)
                        } else 0f
                        var thumbnail by remember(video.id) { mutableStateOf<Bitmap?>(null) }

                        LaunchedEffect(video.id) {
                            thumbnail = VideoRepository.getThumbnail(context, video.id, video.uri, video.path)
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPlayVideo(video, displayedVideos) }
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumbnail != null) {
                                        Image(
                                            bitmap = thumbnail!!.asImageBitmap(),
                                            contentDescription = video.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(Color(0xFF181F2A), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CinePlayPauseIcon(isPlaying = false, tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    // Duration Tag (Bottom Right)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color.Black.copy(alpha = 0.8f),
                                        border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.2f)),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 6.dp, bottom = if (progressFrac > 0f) 8.dp else 6.dp)
                                    ) {
                                        Text(
                                            text = VideoRepository.formatDuration(video.durationMs),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }

                                    if (progressFrac > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .background(Color.White.copy(alpha = 0.22f))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(progressFrac)
                                                    .background(Color(0xFF00E676))
                                            )
                                        }
                                    }
                                }

                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = video.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = VideoRepository.formatFileSize(video.sizeBytes),
                                        color = Color(0xFF8B949E),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 3: X-PLAYER CINEMA SCREEN
    // ==========================================

    @Composable
    fun XPlayerCinemaScreen(
        context: Context,
        bridge: HostBridge,
        player: ExoPlayer,
        video: VideoItem,
        queue: List<VideoItem>,
        isPlaying: Boolean,
        currentPos: Long,
        totalDuration: Long,
        subtitleState: SubtitleState,
        isBackgroundAudioEnabled: Boolean,
        isAutoPlayNextEnabled: Boolean,
        onToggleAutoPlayNext: (Boolean) -> Unit,
        onToggleBackgroundAudio: () -> Unit,
        onSeekTo: (Long) -> Unit,
        onClosePlayer: () -> Unit,
        onSelectVideo: (VideoItem) -> Unit
    ) {
        var showControls by remember { mutableStateOf(true) }
        var isLocked by remember { mutableStateOf(false) }

        var currentSpeed by remember { mutableFloatStateOf(1.0f) }
        var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
        var isMuted by remember { mutableStateOf(false) }
        var currentOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR) }
        val currentConfig = LocalConfiguration.current
        val isLandscape = currentConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
        var showShutterFlash by remember { mutableStateOf(false) }
        var isSavingSnapshot by remember { mutableStateOf(false) }

        val hostActivity = remember(context) {
            var ctx: Context? = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) break
                ctx = ctx.baseContext
            }
            ctx as? Activity
        }
        val isInPip = remember(currentConfig, hostActivity) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (hostActivity?.isInPictureInPictureMode == true)
        }

        fun getLiveVolumePct(): Int {
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                ((cur.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)
            } catch (_: Exception) {
                50
            }
        }

        fun applyWindowBrightness(percentage: Int) {
            val win = hostActivity?.window ?: return
            val clamped = percentage.coerceIn(0, 100)
            val lp = win.attributes
            lp.screenBrightness = if (clamped == 0) 0.01f else clamped / 100f
            win.attributes = lp
        }

        // Precision Gestures State
        var currentBrightness by remember {
            val initial = run {
                val winB = hostActivity?.window?.attributes?.screenBrightness ?: -1f
                if (winB >= 0f) {
                    (winB * 100f).toInt().coerceIn(0, 100)
                } else {
                    try {
                        val sysB = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                        ((sysB / 255f) * 100f).toInt().coerceIn(0, 100)
                    } catch (_: Exception) {
                        50
                    }
                }
            }
            mutableIntStateOf(initial)
        }
        var currentVolume by remember { mutableIntStateOf(getLiveVolumePct()) }
        var hudType by remember { mutableStateOf<String?>(null) } // "BRIGHTNESS", "VOLUME", "SEEK", "DOUBLE_SEEK", "INFO"
        var hudValue by remember { mutableIntStateOf(50) }
        var hudSeekTarget by remember { mutableLongStateOf(0L) }
        var hudSeekDelta by remember { mutableLongStateOf(0L) }
        var hudInfoText by remember { mutableStateOf("") }

        var lastTapTime by remember { mutableLongStateOf(0L) }
        var lastTapPos by remember { mutableStateOf(Offset.Zero) }

        // Player Preferences & Menu State
        val playerPrefs = remember(context) {
            context.getSharedPreferences("omni_player_prefs", Context.MODE_PRIVATE)
        }
        var isGestureSeekEnabled by remember {
            mutableStateOf(playerPrefs.getBoolean("key_gesture_seek_enabled", true))
        }
        var isHold2xEnabled by remember {
            mutableStateOf(playerPrefs.getBoolean("key_hold_2x_enabled", true))
        }
        var showMoreMenu by remember { mutableStateOf(false) }
        var showSpeedMenu by remember { mutableStateOf(false) }

        // Subtitle Suite State
        var activeSubtitleModal by remember { mutableStateOf<String?>(null) } // "HUB", "STYLE", "DOWNLOAD", "AI_INSPECT", "GROQ_KEY", "SETTINGS"
        var isDelayWidgetVisible by remember { mutableStateOf(false) }

        var inspectingCueText by remember { mutableStateOf("") }

        val scope = rememberCoroutineScope()

        fun persistSubtitleImmediately() {
            PlaybackHistoryManager.saveVideoState(
                context = context,
                videoId = video.id.toString(),
                positionMs = player.currentPosition,
                subtitleName = subtitleState.currentSubtitleName,
                rawSrtText = subtitleState.rawSrtContent,
                delaySec = subtitleState.delaySec,
                yFraction = subtitleState.yFraction,
                isEnabled = subtitleState.isEnabled
            )
        }

        fun loadPickedSubtitleUri(pickedUri: Uri) {
            try {
                var displayName: String? = null
                context.contentResolver.query(pickedUri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx)
                    }
                }
                val fileName = displayName?.takeIf { it.isNotBlank() }
                    ?: pickedUri.lastPathSegment?.substringAfterLast("/")
                    ?: "Local Subtitle.srt"

                context.contentResolver.openInputStream(pickedUri)?.bufferedReader()?.use { reader ->
                    val content = reader.readText()
                    subtitleState.loadSrtText(content, fileName)
                    persistSubtitleImmediately()
                    bridge.showToast("Loaded: $fileName")
                }
            } catch (e: Exception) {
                bridge.showToast("Error reading file: ${e.message}")
            }
        }

        val subtitlePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val pickedUri = data.data ?: data.clipData?.getItemAt(0)?.uri
                val parentPath = data.getStringExtra("extra_parent_path")
                val resolvedParent = if (!parentPath.isNullOrEmpty()) {
                    parentPath
                } else {
                    val p = pickedUri?.path
                    if (p != null && (p.contains("/storage/") || p.contains("/sdcard/"))) {
                        val abs = p.substring(p.indexOf("/storage/").coerceAtLeast(p.indexOf("/sdcard/")))
                        File(abs).parent ?: abs
                    } else null
                }
                if (!resolvedParent.isNullOrEmpty() && File(resolvedParent).exists()) {
                    playerPrefs.edit().putString("key_last_subtitle_dir", resolvedParent).apply()
                }
                if (pickedUri != null) {
                    loadPickedSubtitleUri(pickedUri)
                }
            }
        }

        fun openSubtitleFilePicker() {
            val isNexusInstalled = try {
                context.packageManager.getLaunchIntentForPackage("com.conduit.nexus") != null
            } catch (_: Exception) { false }

            if (isNexusInstalled) {
                try {
                    val rememberedPath = playerPrefs.getString("key_last_subtitle_dir", null)
                        ?.takeIf { File(it).exists() }
                    val nexusIntent = Intent("com.conduit.nexus.action.PICK_FILE").apply {
                        setPackage("com.conduit.nexus")
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                        putExtra("extra_picker_mode", true)
                        if (!rememberedPath.isNullOrEmpty()) {
                            putExtra("extra_initial_path", rememberedPath)
                        }
                        type = "*/*"
                    }
                    subtitlePickerLauncher.launch(nexusIntent)
                    return
                } catch (_: Exception) {}
            }

            bridge.pickFiles("*/*", false) { uris ->
                val pickedUri = uris.firstOrNull() ?: return@pickFiles
                val p = pickedUri.path
                if (p != null && (p.contains("/storage/") || p.contains("/sdcard/"))) {
                    val abs = p.substring(p.indexOf("/storage/").coerceAtLeast(p.indexOf("/sdcard/")))
                    val parent = File(abs).parent
                    if (!parent.isNullOrEmpty() && File(parent).exists()) {
                        playerPrefs.edit().putString("key_last_subtitle_dir", parent).apply()
                    }
                }
                loadPickedSubtitleUri(pickedUri)
            }
        }

        BackHandler {
            if (isLocked) {
                isLocked = false
            } else {
                onClosePlayer()
            }
        }

        LaunchedEffect(isInPip) {
            if (isInPip) {
                showControls = false
                showMoreMenu = false
                showSpeedMenu = false
                activeSubtitleModal = null
                isDelayWidgetVisible = false
            }
        }

        // Sync phone system bars (nav + status) with playback controls visibility
        LaunchedEffect(showControls, isLocked, isInPip) {
            if (!isInPip) {
                bridge.setImmersiveMode(!showControls || isLocked)
            }
        }

        // Auto-Hide Controls Timer (4 seconds)
        LaunchedEffect(showControls, isPlaying, isLocked, showMoreMenu, showSpeedMenu, activeSubtitleModal, isInPip) {
            if (showControls && isPlaying && !isLocked && !showMoreMenu && !showSpeedMenu && activeSubtitleModal == null && !isInPip) {
                delay(4000)
                showControls = false
            }
        }

        DisposableEffect(hostActivity) {
            onDispose {
                hostActivity?.window?.let { win ->
                    val lp = win.attributes
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    win.attributes = lp
                }
                bridge.setImmersiveMode(false)
                bridge.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 1. AndroidView ExoPlayer Video Surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = currentResizeMode
                        playerViewRef = this
                    }
                },
                update = { pv ->
                    pv.resizeMode = currentResizeMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // 1b. Dedicated Video Gesture Layer (behind HUD, buttons, subtitles & modals)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isLocked, activeSubtitleModal, isGestureSeekEnabled, isHold2xEnabled, isInPip) {
                        if (activeSubtitleModal != null || isInPip) return@pointerInput
                        awaitPointerEventScope {
                            val density = this.density
                            val touchSlopPx = 18f * density

                            while (true) {
                                val downEvent = awaitPointerEvent()
                                if (downEvent.type != PointerEventType.Press) continue
                                val downChange = downEvent.changes.firstOrNull() ?: continue
                                if (downChange.isConsumed) continue
                                val startX = downChange.position.x
                                val startY = downChange.position.y
                                val sWidth = size.width.toFloat()
                                val sHeight = size.height.toFloat()

                                var isDragging = false
                                var gestureMode = 0 // 1: Brightness, 2: Volume, 3: Seek

                                val initialB = currentBrightness
                                val initialV = getLiveVolumePct().also { currentVolume = it }

                                var isHold2xActive = false
                                var speedBeforeHold = currentSpeed
                                val holdJob = if (isHold2xEnabled && !isLocked) {
                                    scope.launch {
                                        delay(380L)
                                        if (!isDragging && !isLocked) {
                                            isHold2xActive = true
                                            speedBeforeHold = currentSpeed
                                            player.playbackParameters = PlaybackParameters(2.0f)
                                            hudType = "HOLD_2X"
                                            bridge.vibrate(25L)
                                        }
                                    }
                                } else null

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == downChange.id } ?: break

                                        if (event.type == PointerEventType.Move) {
                                            if (change.isConsumed) {
                                                holdJob?.cancel()
                                                break
                                            }
                                            val totalDx = change.position.x - startX
                                            val totalDy = change.position.y - startY
                                            val dist = kotlin.math.hypot(totalDx, totalDy)

                                            if (!isDragging && !isHold2xActive && dist > touchSlopPx && !isLocked) {
                                                holdJob?.cancel()
                                                isDragging = true
                                                if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)) {
                                                    gestureMode = if (startX < sWidth * 0.45f) 1 else if (startX > sWidth * 0.55f) 2 else 0
                                                } else {
                                                    gestureMode = if (isGestureSeekEnabled) 3 else 0
                                                }
                                            }

                                            if (isDragging) {
                                                change.consume()
                                                when (gestureMode) {
                                                    1 -> {
                                                        val deltaY = startY - change.position.y
                                                        val newB = (initialB + (deltaY / sHeight * 100f)).toInt().coerceIn(0, 100)
                                                        currentBrightness = newB
                                                        applyWindowBrightness(newB)
                                                        hudType = "BRIGHTNESS"
                                                        hudValue = newB
                                                    }
                                                    2 -> {
                                                        val deltaY = startY - change.position.y
                                                        val newV = (initialV + (deltaY / sHeight * 100f)).toInt().coerceIn(0, 100)
                                                        currentVolume = newV
                                                        bridge.setVolume("MEDIA", newV)
                                                        hudType = "VOLUME"
                                                        hudValue = newV
                                                    }
                                                    3 -> {
                                                        val deltaX = change.position.x - startX
                                                        val deltaMs = (deltaX * 120f).toLong()
                                                        val targetMs = (player.currentPosition + deltaMs).coerceIn(0L, totalDuration)
                                                        hudType = "SEEK"
                                                        hudSeekTarget = targetMs
                                                        hudSeekDelta = deltaMs / 1000L
                                                    }
                                                }
                                            }
                                        } else if (event.type == PointerEventType.Release) {
                                            holdJob?.cancel()
                                            if (isHold2xActive) {
                                                player.playbackParameters = PlaybackParameters(speedBeforeHold)
                                                if (hudType == "HOLD_2X") hudType = null
                                                isHold2xActive = false
                                                change.consume()
                                                break
                                            }
                                            if (change.isConsumed) break
                                            change.consume()
                                            if (isDragging) {
                                                if (gestureMode == 3) {
                                                    player.seekTo(hudSeekTarget)
                                                }
                                                scope.launch {
                                                    delay(900)
                                                    hudType = null
                                                }
                                            } else {
                                                // Stationary Tap or Double-tap
                                                val now = System.currentTimeMillis()
                                                val isQuickSecondTap = (now - lastTapTime < 320L) && (kotlin.math.abs(startX - lastTapPos.x) < 50f * density)

                                                if (isQuickSecondTap) {
                                                    // Double-Tap Execution
                                                    lastTapTime = 0L
                                                    if (!isLocked) {
                                                        when {
                                                            startX < sWidth * 0.35f -> {
                                                                if (isGestureSeekEnabled) {
                                                                    onSeekTo(player.currentPosition - 10000)
                                                                    hudType = "DOUBLE_SEEK"
                                                                    hudSeekDelta = -10L
                                                                    scope.launch { delay(700); hudType = null }
                                                                }
                                                            }
                                                            startX > sWidth * 0.65f -> {
                                                                if (isGestureSeekEnabled) {
                                                                    onSeekTo(player.currentPosition + 10000)
                                                                    hudType = "DOUBLE_SEEK"
                                                                    hudSeekDelta = 10L
                                                                    scope.launch { delay(700); hudType = null }
                                                                }
                                                            }
                                                            else -> {
                                                                if (player.isPlaying) player.pause() else player.play()
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // First Tap Registered - Toggle Controls after double-tap window expires
                                                    lastTapTime = now
                                                    lastTapPos = Offset(startX, startY)
                                                    scope.launch {
                                                        delay(320L)
                                                        if (lastTapTime == now) {
                                                            showControls = !showControls
                                                        }
                                                    }
                                                }
                                            }
                                            break
                                        }
                                    }
                                } finally {
                                    holdJob?.cancel()
                                    if (isHold2xActive) {
                                        player.playbackParameters = PlaybackParameters(speedBeforeHold)
                                        if (hudType == "HOLD_2X") hudType = null
                                    }
                                }
                            }
                        }
                    }
            )

            // 2. Left Edge-Anchored Brightness HUD
            if (!isInPip && hudType == "BRIGHTNESS") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp)
                        .width(48.dp)
                        .height(180.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CineSunIcon(tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))

                        // Vertical Progress Track
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(90.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(hudValue / 100f)
                                    .background(Color(0xFF00E676))
                            )
                        }

                        Text(
                            text = "$hudValue%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 3. Right Edge-Anchored Volume HUD
            if (!isInPip && hudType == "VOLUME") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 28.dp)
                        .width(48.dp)
                        .height(180.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CineVolumeIcon(isMuted = false, tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))

                        // Vertical Progress Track
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(90.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(hudValue / 100f)
                                    .background(Color(0xFF00E676))
                            )
                        }

                        Text(
                            text = "$hudValue%",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 4. Center / Top Seeking / Fast-Forward / Hold-2X / Info HUD
            if (!isInPip && hudType == "HOLD_2X") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF00E676)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                ) {
                    Text(
                        text = "▶▶  2.0X SPEED",
                        color = Color(0xFF00E676),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            if (!isInPip && hudType == "INFO" && hudInfoText.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = hudInfoText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            if (!isInPip && (hudType == "SEEK" || hudType == "DOUBLE_SEEK")) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CineSeekArcIcon(isForward = hudSeekDelta >= 0, seconds = 10, tint = Color(0xFF00E676), modifier = Modifier.size(26.dp))
                        Column {
                            if (hudType == "SEEK") {
                                Text(
                                    text = VideoRepository.formatDuration(hudSeekTarget),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (hudSeekDelta >= 0) "[+${hudSeekDelta}s]" else "[-${kotlin.math.abs(hudSeekDelta)}s]",
                                    color = Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                Text(
                                    text = if (hudSeekDelta >= 0) "+10 SECONDS" else "-10 SECONDS",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // 5. Floating Screen Lock Key (Disappears on its spot)
            AnimatedVisibility(
                visible = (showControls || isLocked) && !isInPip,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, if (isLocked) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .clickable {
                            isLocked = !isLocked
                            bridge.vibrate(50L)
                            bridge.showToast(if (isLocked) "Touch Controls Locked" else "Touch Controls Unlocked")
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CineLockIcon(isLocked = isLocked, tint = if (isLocked) Color(0xFF00E676) else Color.White)
                    }
                }
            }

            // 6. Floating Camera Shutter (Disappears on its spot)
            AnimatedVisibility(
                visible = showControls && !isLocked && !isInPip,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250)),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (isSavingSnapshot) return@clickable
                            isSavingSnapshot = true
                            bridge.vibrate(60L)
                            val snapPosMs = player.currentPosition
                            val activeSub = subtitleState.getCurrentCue(snapPosMs)
                            val subFontSp = subtitleState.fontSizeSp
                            val subColorArgb = subtitleState.fontColor.toArgb()
                            val subBoxArgb = subtitleState.boxColor.toArgb()
                            val subOutline = subtitleState.hasOutline
                            val subYFrac = subtitleState.yFraction

                            scope.launch {
                                try {
                                    val frame = VideoSnapshotHelper.grabVideoFrame(
                                        context = context,
                                        video = video,
                                        positionMs = snapPosMs,
                                        hostActivity = hostActivity,
                                        playerView = playerViewRef
                                    )
                                    if (frame == null) {
                                        bridge.showToast("Could not capture video frame")
                                        return@launch
                                    }
                                    showShutterFlash = true
                                    launch {
                                        delay(110L)
                                        showShutterFlash = false
                                    }
                                    val savedName = withContext(Dispatchers.IO) {
                                        VideoSnapshotHelper.burnSubtitleAndSaveJpeg(
                                            context = context,
                                            rawFrame = frame,
                                            videoName = video.name,
                                            subtitleText = activeSub,
                                            fontSizeSp = subFontSp,
                                            fontColorArgb = subColorArgb,
                                            boxColorArgb = subBoxArgb,
                                            hasOutline = subOutline,
                                            yFraction = subYFrac
                                        )
                                    }
                                    if (savedName != null) {
                                        bridge.showToast("Saved: Pictures/OmniSnapshots/$savedName")
                                    } else {
                                        bridge.showToast("Failed saving snapshot to Gallery")
                                    }
                                } finally {
                                    isSavingSnapshot = false
                                }
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CineCameraIcon(tint = Color.White)
                    }
                }
            }

            AnimatedVisibility(
                visible = showShutterFlash,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(40)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(160))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.65f))
                )
            }

            // 7. X-PLAYER CINEMA OVERLAYS (FADES IN-PLACE, NO SLIDING)
            AnimatedVisibility(
                visible = showControls && !isLocked && !isInPip,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // --- TOP BAR WITH CINEMA GRADIENT SCRIM ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.85f),
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(onClick = onClosePlayer) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = video.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontStyle = FontStyle.Italic,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            showControls = false
                                            showMoreMenu = false
                                            showSpeedMenu = false
                                            val builder = android.app.PictureInPictureParams.Builder()
                                            val vs = player.videoSize
                                            if (vs.width > 0 && vs.height > 0) {
                                                val ratio = vs.width.toFloat() / vs.height.toFloat()
                                                if (ratio in 0.45f..2.35f) {
                                                    builder.setAspectRatio(Rational(vs.width, vs.height))
                                                }
                                            }
                                            hostActivity?.enterPictureInPictureMode(builder.build())
                                        }
                                    }) {
                                        CinePipIcon(tint = Color.White)
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (subtitleState.currentSubtitleName != null && subtitleState.isEnabled) Color(0xFF00E676) else Color.White.copy(alpha = 0.8f)
                                        ),
                                        color = if (subtitleState.currentSubtitleName != null && subtitleState.isEnabled) Color(0xFF00E676).copy(alpha = 0.25f) else Color.Transparent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { activeSubtitleModal = "HUB" }
                                    ) {
                                        Text(
                                            text = "CC",
                                            color = if (subtitleState.currentSubtitleName != null && subtitleState.isEnabled) Color(0xFF00E676) else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Box {
                                        IconButton(onClick = { showMoreMenu = true }) {
                                            CineMoreIcon(tint = Color.White)
                                        }
                                        DropdownMenu(
                                            expanded = showMoreMenu,
                                            onDismissRequest = { showMoreMenu = false },
                                            modifier = Modifier.background(Color(0xFF161B22))
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Audio & Tracks",
                                                        color = Color(0xFF00E676),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                },
                                                onClick = {
                                                    showMoreMenu = false
                                                    activeSubtitleModal = "TRACKS"
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Settings",
                                                        color = Color.White,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                },
                                                onClick = {
                                                    showMoreMenu = false
                                                    activeSubtitleModal = "SETTINGS"
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Show Decks",
                                                        color = Color(0xFF58A6FF),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                },
                                                onClick = {
                                                    showMoreMenu = false
                                                    bridge.showRecents()
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Sub-Header Frosted Quick Pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            currentOrientation = if (isLandscape) {
                                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            } else {
                                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                            }
                                            bridge.setScreenOrientation(currentOrientation)
                                        }
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        CineRotateIcon(tint = Color.White)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            isMuted = !isMuted
                                            player.volume = if (isMuted) 0f else 1f
                                        }
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        CineVolumeIcon(isMuted = isMuted, tint = Color.White)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isBackgroundAudioEnabled) Color(0xFF00E676) else Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (isBackgroundAudioEnabled) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onToggleBackgroundAudio() }
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        CineHeadphonesIcon(
                                            tint = if (isBackgroundAudioEnabled) Color.Black else Color.White
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (currentSpeed != 1.0f) Color(0xFF00E676).copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (currentSpeed != 1.0f) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            currentSpeed = when (currentSpeed) {
                                                1.0f -> 1.25f
                                                1.25f -> 1.5f
                                                1.5f -> 2.0f
                                                2.0f -> 0.5f
                                                else -> 1.0f
                                            }
                                            player.playbackParameters = PlaybackParameters(currentSpeed)
                                            hudInfoText = "Speed: ${currentSpeed}X"
                                            hudType = "INFO"
                                            scope.launch {
                                                delay(800)
                                                if (hudType == "INFO") hudType = null
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "${currentSpeed}X",
                                        color = if (currentSpeed != 1.0f) Color(0xFF00E676) else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // --- BOTTOM BAR WITH CINEMA GRADIENT SCRIM ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Black.copy(alpha = 0.90f)
                                    )
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Column {
                            // Electric Green Razor Scrubber
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = VideoRepository.formatDuration(currentPos),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )

                                Slider(
                                    value = currentPos.coerceIn(0L, totalDuration.coerceAtLeast(1L)).toFloat(),
                                    onValueChange = { targetPos ->
                                        onSeekTo(targetPos.toLong())
                                    },
                                    valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color(0xFF00E676),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp)
                                )

                                Text(
                                    text = VideoRepository.formatDuration(totalDuration),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            // Controls Row
                            val curQueueIdx = queue.indexOfFirst { it.id == video.id }
                            val hasPrevVideo = curQueueIdx > 0
                            val hasNextVideo = curQueueIdx >= 0 && curQueueIdx < queue.lastIndex

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { isLocked = true }) {
                                    CineLockIcon(isLocked = false, tint = Color.White)
                                }

                                IconButton(onClick = {
                                    player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                                }) {
                                    CineSeekArcIcon(isForward = false, seconds = 10, tint = Color.White)
                                }

                                IconButton(
                                    onClick = {
                                        if (hasPrevVideo) onSelectVideo(queue[curQueueIdx - 1])
                                    },
                                    enabled = hasPrevVideo
                                ) {
                                    CineSkipIcon(
                                        isNext = false,
                                        tint = Color.White.copy(alpha = if (hasPrevVideo) 1f else 0.35f)
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.15f),
                                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (player.isPlaying) player.pause() else player.play()
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CinePlayPauseIcon(isPlaying = isPlaying, tint = Color.White)
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (hasNextVideo) onSelectVideo(queue[curQueueIdx + 1])
                                    },
                                    enabled = hasNextVideo
                                ) {
                                    CineSkipIcon(
                                        isNext = true,
                                        tint = Color.White.copy(alpha = if (hasNextVideo) 1f else 0.35f)
                                    )
                                }

                                IconButton(onClick = {
                                    player.seekTo((player.currentPosition + 10000).coerceAtMost(totalDuration))
                                }) {
                                    CineSeekArcIcon(isForward = true, seconds = 10, tint = Color.White)
                                }

                                Box {
                                    Text(
                                        text = if (currentSpeed == 1.0f) "Speed" else "${currentSpeed}X",
                                        color = if (currentSpeed != 1.0f) Color(0xFF00E676) else Color.White,
                                        fontSize = 12.sp,
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = if (currentSpeed != 1.0f) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showSpeedMenu = true }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    DropdownMenu(
                                        expanded = showSpeedMenu,
                                        onDismissRequest = { showSpeedMenu = false },
                                        modifier = Modifier.background(Color(0xFF161B22))
                                    ) {
                                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { spd ->
                                            val isSelected = currentSpeed == spd
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "${spd}X",
                                                        color = if (isSelected) Color(0xFF00E676) else Color.White,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 13.sp
                                                    )
                                                },
                                                onClick = {
                                                    currentSpeed = spd
                                                    player.playbackParameters = PlaybackParameters(spd)
                                                    showSpeedMenu = false
                                                    hudInfoText = "Speed: ${spd}X"
                                                    hudType = "INFO"
                                                    scope.launch {
                                                        delay(800)
                                                        if (hudType == "INFO") hudType = null
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                IconButton(onClick = {
                                    val (nextMode, label) = when (currentResizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Aspect: Zoom (Crop)"
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL to "Aspect: Stretch"
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to "Aspect: Fit"
                                    }
                                    currentResizeMode = nextMode
                                    hudInfoText = label
                                    hudType = "INFO"
                                    scope.launch {
                                        delay(800)
                                        if (hudType == "INFO") hudType = null
                                    }
                                }) {
                                    CineAspectIcon(tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // --- LIVE ON-VIDEO SUBTITLE CUE OVERLAY (TAP TO AI-INSPECT, HOLD & DRAG TO MOVE) ---
            val activeCueText = remember(currentPos, subtitleState.delaySec, subtitleState.cues.size, subtitleState.isEnabled) {
                subtitleState.getCurrentCue(currentPos)
            }
            SubtitleOnVideoOverlay(
                cueText = activeCueText,
                subtitleState = subtitleState,
                onPositionChanged = { newFraction ->
                    bridge.vibrate(30L)
                    PlaybackHistoryManager.saveVideoState(
                        context = context,
                        videoId = video.id.toString(),
                        positionMs = player.currentPosition,
                        subtitleName = subtitleState.currentSubtitleName,
                        rawSrtText = subtitleState.rawSrtContent,
                        delaySec = subtitleState.delaySec,
                        yFraction = newFraction,
                        isEnabled = subtitleState.isEnabled
                    )
                },
                onTapSubtitle = {
                    if (!activeCueText.isNullOrBlank()) {
                        player.pause()
                        bridge.vibrate(35L)
                        inspectingCueText = activeCueText
                        if (!GroqAiService.isKeyConfigured(context)) {
                            activeSubtitleModal = "GROQ_KEY"
                        } else {
                            activeSubtitleModal = "AI_INSPECT"
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // --- FLOATING SUBTITLE DELAY ADJUSTER (Screenshot 2) ---
            if (!isInPip && isDelayWidgetVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                ) {
                    SubtitleDelayWidget(
                        subtitleState = subtitleState,
                        onDelayChanged = { persistSubtitleImmediately() },
                        onClose = { isDelayWidgetVisible = false }
                    )
                }
            }

            // --- SUBTITLE SUITE MODALS ---
            when (if (isInPip) null else activeSubtitleModal) {
                "HUB" -> {
                    SubtitleHubSheet(
                        subtitleState = subtitleState,
                        onOpenFile = {
                            activeSubtitleModal = null
                            openSubtitleFilePicker()
                        },
                        onOpenDownload = {
                            activeSubtitleModal = "DOWNLOAD"
                        },
                        onOpenStyle = {
                            activeSubtitleModal = "STYLE"
                        },
                        onOpenDelay = {
                            activeSubtitleModal = null
                            isDelayWidgetVisible = true
                        },
                        onStateChanged = { persistSubtitleImmediately() },
                        onClose = { activeSubtitleModal = null }
                    )
                }
                "STYLE" -> {
                    SubtitleStyleSheet(
                        subtitleState = subtitleState,
                        onStyleChanged = { subtitleState.saveStylePrefs(context) },
                        onBack = { activeSubtitleModal = "HUB" }
                    )
                }
                "DOWNLOAD" -> {
                    OnlineSubtitleSearchModal(
                        videoName = video.name,
                        onSelectSubtitle = { srtText, label ->
                            subtitleState.loadSrtText(srtText, label)
                            persistSubtitleImmediately()
                            bridge.showToast("Subtitles loaded: $label")
                            activeSubtitleModal = null
                        },
                        onClose = { activeSubtitleModal = "HUB" }
                    )
                }
                "TRACKS" -> {
                    AudioAndTracksDialog(
                        player = player,
                        onDismiss = { activeSubtitleModal = null }
                    )
                }
                "SETTINGS" -> {
                    PlayerSettingsDialog(
                        isGestureSeekEnabled = isGestureSeekEnabled,
                        onToggleGestureSeek = { enabled ->
                            isGestureSeekEnabled = enabled
                            playerPrefs.edit().putBoolean("key_gesture_seek_enabled", enabled).apply()
                        },
                        isHold2xEnabled = isHold2xEnabled,
                        onToggleHold2x = { enabled ->
                            isHold2xEnabled = enabled
                            playerPrefs.edit().putBoolean("key_hold_2x_enabled", enabled).apply()
                        },
                        isAutoPlayNextEnabled = isAutoPlayNextEnabled,
                        onToggleAutoPlayNext = onToggleAutoPlayNext,
                        onDismiss = { activeSubtitleModal = null }
                    )
                }
                "AI_INSPECT" -> {
                    AiDialogueInspectorSheet(
                        context = context,
                        currentCue = inspectingCueText,
                        onConfigureKey = { activeSubtitleModal = "GROQ_KEY" },
                        onResumeMovie = {
                            activeSubtitleModal = null
                            player.play()
                        },
                        onClose = { activeSubtitleModal = null }
                    )
                }
                "GROQ_KEY" -> {
                    GroqKeyConfigDialog(
                        context = context,
                        onKeySaved = {
                            bridge.showToast("Groq AI Settings Saved!")
                            if (inspectingCueText.isNotBlank()) {
                                activeSubtitleModal = "AI_INSPECT"
                            } else {
                                activeSubtitleModal = null
                            }
                        },
                        onDismiss = { activeSubtitleModal = null }
                    )
                }
            }
        }
    }

    @Composable
    fun AudioAndTracksDialog(
        player: ExoPlayer,
        onDismiss: () -> Unit
    ) {
        var selectionVersion by remember { mutableIntStateOf(0) }
        val currentTracks = remember(selectionVersion) { player.currentTracks }

        val audioTracks = remember(currentTracks) {
            val items = mutableListOf<Triple<androidx.media3.common.Tracks.Group, Int, String>>()
            currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { gIdx, group ->
                for (tIdx in 0 until group.length) {
                    if (!group.isTrackSupported(tIdx)) continue
                    val fmt = group.getTrackFormat(tIdx)
                    val lang = fmt.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase(Locale.US)
                    val label = fmt.label?.takeIf { it.isNotBlank() }
                    val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else null
                    val title = listOfNotNull(label, lang, ch).joinToString(" • ").ifEmpty { "Audio Track ${gIdx + 1}" }
                    items.add(Triple(group, tIdx, title))
                }
            }
            items
        }

        val textTracks = remember(currentTracks) {
            val items = mutableListOf<Triple<androidx.media3.common.Tracks.Group, Int, String>>()
            currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEachIndexed { gIdx, group ->
                for (tIdx in 0 until group.length) {
                    if (!group.isTrackSupported(tIdx)) continue
                    val fmt = group.getTrackFormat(tIdx)
                    val lang = fmt.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase(Locale.US)
                    val label = fmt.label?.takeIf { it.isNotBlank() }
                    val title = listOfNotNull(label, lang).joinToString(" • ").ifEmpty { "Embedded Sub ${gIdx + 1}" }
                    items.add(Triple(group, tIdx, title))
                }
            }
            items
        }

        val isAnyTextSelected = textTracks.any { (g, idx, _) -> g.isTrackSelected(idx) }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF11151D),
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Audio & Tracks", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Built-in Video Streams", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8B949E))
                    }
                }
            },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    item {
                        Text("AUDIO TRACKS", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    if (audioTracks.isEmpty()) {
                        item {
                            Text("No selectable audio tracks found.", color = Color(0xFF484F58), fontSize = 12.sp)
                        }
                    } else {
                        items(audioTracks) { (group, tIdx, title) ->
                            val selected = group.isTrackSelected(tIdx)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFF00E676).copy(alpha = 0.16f) else Color(0xFF161B22),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF00E676) else Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, tIdx))
                                            .build()
                                        selectionVersion++
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = title,
                                        color = if (selected) Color(0xFF00E676) else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (selected) {
                                        Text("ACTIVE", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (textTracks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text("EMBEDDED SUBTITLES", color = Color(0xFF8B949E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (!isAnyTextSelected) Color(0xFF00E676).copy(alpha = 0.16f) else Color(0xFF161B22),
                                border = BorderStroke(1.dp, if (!isAnyTextSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                        selectionVersion++
                                    }
                            ) {
                                Text(
                                    text = "Off (Use External .srt / None)",
                                    color = if (!isAnyTextSelected) Color(0xFF00E676) else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (!isAnyTextSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                        items(textTracks) { (group, tIdx, title) ->
                            val selected = group.isTrackSelected(tIdx)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFF00E676).copy(alpha = 0.16f) else Color(0xFF161B22),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF00E676) else Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, tIdx))
                                            .build()
                                        selectionVersion++
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = title,
                                        color = if (selected) Color(0xFF00E676) else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (selected) {
                                        Text("ACTIVE", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    @Composable
    fun PlayerSettingsDialog(
        isGestureSeekEnabled: Boolean,
        onToggleGestureSeek: (Boolean) -> Unit,
        isHold2xEnabled: Boolean,
        onToggleHold2x: (Boolean) -> Unit,
        isAutoPlayNextEnabled: Boolean,
        onToggleAutoPlayNext: (Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF11151D),
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Player Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Playback & Gesture Controls", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8B949E))
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable
                    fun SettingSwitchCard(
                        title: String,
                        subtitle: String,
                        checked: Boolean,
                        onCheckedChange: (Boolean) -> Unit
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onCheckedChange(!checked) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = subtitle,
                                        color = Color(0xFF8B949E),
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = checked,
                                    onCheckedChange = onCheckedChange,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF00E676),
                                        uncheckedThumbColor = Color(0xFF8B949E),
                                        uncheckedTrackColor = Color(0xFF21262D)
                                    )
                                )
                            }
                        }
                    }

                    SettingSwitchCard(
                        title = "Gesture Seeking",
                        subtitle = "Swipe horizontally or double-tap edges to seek ±10s",
                        checked = isGestureSeekEnabled,
                        onCheckedChange = onToggleGestureSeek
                    )

                    SettingSwitchCard(
                        title = "Hold Screen for 2.0X",
                        subtitle = "Press and hold video to fast-forward at 2X until released",
                        checked = isHold2xEnabled,
                        onCheckedChange = onToggleHold2x
                    )

                    SettingSwitchCard(
                        title = "Auto-Play Next Video",
                        subtitle = "Automatically play next episode/video in folder when finished",
                        checked = isAutoPlayNextEnabled,
                        onCheckedChange = onToggleAutoPlayNext
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    @Composable
    fun OnlineSubtitleSearchModal(
        videoName: String,
        onSelectSubtitle: (String, String) -> Unit,
        onClose: () -> Unit
    ) {
        var queryText by remember { mutableStateOf(OpenSubtitlesService.cleanTitle(videoName)) }
        var isSearching by remember { mutableStateOf(false) }
        var isDownloadingId by remember { mutableStateOf<Long?>(null) }
        var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun triggerSearch() {
            if (queryText.isBlank()) return
            scope.launch {
                isSearching = true
                errorMessage = null
                val response = OpenSubtitlesService.search(queryText.trim(), "en")
                results = response.results
                errorMessage = response.error
                isSearching = false
            }
        }

        LaunchedEffect(Unit) {
            triggerSearch()
        }

        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Color(0xFF11151D),
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Online Subtitles", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("OpenSubtitles Database", color = Color(0xFF00E676), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8B949E))
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E676),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { triggerSearch() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Find", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }



                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(6.dp)
                    ) {
                        if (isSearching) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp)
                            }
                        } else if (errorMessage != null && results.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = errorMessage!!,
                                        color = Color(0xFFF85149),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { triggerSearch() }) {
                                        Text("Retry", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else if (results.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No subtitles found", color = Color(0xFF8B949E), fontSize = 12.sp)
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (errorMessage != null) {
                                    Text(
                                        text = errorMessage!!,
                                        color = Color(0xFFF85149),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(results, key = { it.fileId }) { item ->
                                        val isDownloading = isDownloadingId == item.fileId
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF161B22),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !isDownloading) {
                                                    isDownloadingId = item.fileId
                                                    errorMessage = null
                                                    scope.launch {
                                                        val (srtText, dlErr) = OpenSubtitlesService.downloadSubtitleWithStatus(item.fileId)
                                                        isDownloadingId = null
                                                        if (srtText != null) {
                                                            onSelectSubtitle(srtText, "${item.language.uppercase()} - ${item.releaseName.take(30)}")
                                                        } else {
                                                            errorMessage = dlErr ?: "Failed downloading subtitle"
                                                        }
                                                    }
                                                }
                                        ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.releaseName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${item.language.uppercase()} • ↓ ${item.downloadCount}", color = Color(0xFF8B949E), fontSize = 10.sp)
                                            }
                                            if (isDownloading) {
                                                CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                            } else {
                                                Text("Get", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            },
            confirmButton = {}
        )
    }
}