package com.omni.plugin.player

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        when {
            currentPlayingVideo != null -> {
                XPlayerCinemaScreen(
                    context = context,
                    bridge = bridge,
                    video = currentPlayingVideo!!,
                    queue = videoQueue,
                    onClosePlayer = {
                        bridge.setImmersiveMode(false)
                        bridge.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                        currentPlayingVideo = null
                    },
                    onSelectVideo = { nextVideo ->
                        currentPlayingVideo = nextVideo
                    }
                )
            }
            currentFolder != null -> {
                FolderVideosScreen(
                    context = context,
                    folder = currentFolder!!,
                    onBack = { currentFolder = null },
                    onPlayVideo = { video, queue ->
                        videoQueue = queue
                        currentPlayingVideo = video
                    }
                )
            }
            else -> {
                FolderCatalogScreen(
                    context = context,
                    bridge = bridge,
                    onOpenFolder = { folder -> currentFolder = folder }
                )
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
        onOpenFolder: (VideoFolder) -> Unit
    ) {
        var folders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        fun loadData() {
            scope.launch {
                isLoading = true
                folders = VideoRepository.getFolders(context)
                isLoading = false
            }
        }

        LaunchedEffect(Unit) {
            loadData()
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
        onBack: () -> Unit,
        onPlayVideo: (VideoItem, List<VideoItem>) -> Unit
    ) {
        var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        BackHandler { onBack() }

        LaunchedEffect(folder.name) {
            isLoading = true
            videos = VideoRepository.getVideosInFolder(context, folder.name)
            isLoading = false
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(folder.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${videos.size} videos available", color = Color(0xFF8B949E), fontSize = 11.sp)
                }
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
                    items(videos) { video ->
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
                                .clickable { onPlayVideo(video, videos) }
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
                                            .padding(6.dp)
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
        video: VideoItem,
        queue: List<VideoItem>,
        onClosePlayer: () -> Unit,
        onSelectVideo: (VideoItem) -> Unit
    ) {
        val player = remember(context) {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }

        var isPlaying by remember { mutableStateOf(true) }
        var currentPos by remember { mutableLongStateOf(0L) }
        var totalDuration by remember { mutableLongStateOf(video.durationMs.coerceAtLeast(1L)) }
        var showControls by remember { mutableStateOf(true) }
        var isLocked by remember { mutableStateOf(false) }

        var currentSpeed by remember { mutableFloatStateOf(1.0f) }
        var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
        var isMuted by remember { mutableStateOf(false) }
        var currentOrientation by remember { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR) }

        val hostActivity = remember(context) {
            var ctx: Context? = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) break
                ctx = ctx.baseContext
            }
            ctx as? Activity
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
        var currentVolume by remember { mutableIntStateOf(50) }
        var hudType by remember { mutableStateOf<String?>(null) } // "BRIGHTNESS", "VOLUME", "SEEK", "DOUBLE_SEEK"
        var hudValue by remember { mutableIntStateOf(50) }
        var hudSeekTarget by remember { mutableLongStateOf(0L) }
        var hudSeekDelta by remember { mutableLongStateOf(0L) }

        var lastTapTime by remember { mutableLongStateOf(0L) }
        var lastTapPos by remember { mutableStateOf(Offset.Zero) }

        // Subtitle Suite State
        val subtitleState = remember { SubtitleState() }
        var activeSubtitleModal by remember { mutableStateOf<String?>(null) } // "HUB", "STYLE", "DOWNLOAD", "AI_INSPECT", "GROQ_KEY"
        var isDelayWidgetVisible by remember { mutableStateOf(false) }

        var inspectingCueText by remember { mutableStateOf("") }

        val scope = rememberCoroutineScope()

        BackHandler {
            if (isLocked) {
                isLocked = false
            } else {
                onClosePlayer()
            }
        }

        // Initialize Player with Video and Restore Memory Vault State
        LaunchedEffect(video.uri) {
            bridge.setImmersiveMode(true)
            val savedState = PlaybackHistoryManager.getSavedState(context, video.id.toString())
            if (savedState != null) {
                subtitleState.delaySec = savedState.optDouble("delaySec", 0.0).toFloat()
                subtitleState.yFraction = savedState.optDouble("yFraction", 0.08).toFloat()
                subtitleState.isEnabled = savedState.optBoolean("isEnabled", true)
            }
            val cachedSub = PlaybackHistoryManager.getSavedSubtitle(context, video.id.toString())
            if (cachedSub != null) {
                subtitleState.loadSrtText(cachedSub.second, cachedSub.first)
            }

            val mediaItem = MediaItem.fromUri(video.uri)
            val resumeMs = savedState?.optLong("positionMs", 0L) ?: 0L
            player.setMediaItem(mediaItem, resumeMs)
            player.prepare()
            player.play()
            isPlaying = true
        }

        // Track Timeline Progress
        LaunchedEffect(player, isPlaying) {
            while (isActive) {
                if (player.isPlaying) {
                    currentPos = player.currentPosition
                    if (player.duration > 0) totalDuration = player.duration
                }
                delay(200)
            }
        }

        // Auto-Hide Controls Timer (4 seconds)
        LaunchedEffect(showControls, isPlaying, isLocked) {
            if (showControls && isPlaying && !isLocked) {
                delay(4000)
                showControls = false
            }
        }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (!playing) {
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
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (player.duration > 0) totalDuration = player.duration
                    }
                }
            }
            player.addListener(listener)
            onDispose {
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
                hostActivity?.window?.let { win ->
                    val lp = win.attributes
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    win.attributes = lp
                }
                bridge.setImmersiveMode(false)
                bridge.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                player.removeListener(listener)
                player.release()
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
                    .pointerInput(isLocked, activeSubtitleModal) {
                        if (activeSubtitleModal != null) return@pointerInput
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
                                val initialV = currentVolume

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == downChange.id } ?: break

                                    if (event.type == PointerEventType.Move) {
                                        if (change.isConsumed) break
                                        val totalDx = change.position.x - startX
                                        val totalDy = change.position.y - startY
                                        val dist = kotlin.math.hypot(totalDx, totalDy)

                                        if (!isDragging && dist > touchSlopPx && !isLocked) {
                                            isDragging = true
                                            if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)) {
                                                gestureMode = if (startX < sWidth * 0.45f) 1 else if (startX > sWidth * 0.55f) 2 else 0
                                            } else {
                                                gestureMode = 3
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
                                                            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                                                            hudType = "DOUBLE_SEEK"
                                                            hudSeekDelta = -10L
                                                            scope.launch { delay(700); hudType = null }
                                                        }
                                                        startX > sWidth * 0.65f -> {
                                                            player.seekTo((player.currentPosition + 10000).coerceAtMost(totalDuration))
                                                            hudType = "DOUBLE_SEEK"
                                                            hudSeekDelta = 10L
                                                            scope.launch { delay(700); hudType = null }
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
                            }
                        }
                    }
            )

            // 2. Left Edge-Anchored Brightness HUD
            if (hudType == "BRIGHTNESS") {
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
            if (hudType == "VOLUME") {
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

            // 4. Center Seeking / Fast-Forward HUD
            if (hudType == "SEEK" || hudType == "DOUBLE_SEEK") {
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
                visible = showControls || isLocked,
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
                visible = showControls && !isLocked,
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
                            bridge.vibrate(100L)
                            bridge.showToast("Snapshot saved to device gallery")
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CineCameraIcon(tint = Color.White)
                    }
                }
            }

            // 7. X-PLAYER CINEMA OVERLAYS (FADES IN-PLACE, NO SLIDING)
            AnimatedVisibility(
                visible = showControls && !isLocked,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(250))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
                                        val act = context as? android.app.Activity
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            act?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
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

                                    IconButton(onClick = { bridge.showToast("Aspect / Audio Settings") }) {
                                        CineMoreIcon(tint = Color.White)
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
                                            currentOrientation = if (currentOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            } else {
                                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
                                    color = Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            bridge.startMediaPlayback(video.name, "Omni Background Player", true) { playState ->
                                                if (playState) player.play() else player.pause()
                                            }
                                            bridge.showToast("Background audio mode armed")
                                        }
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        CineHeadphonesIcon(tint = Color.White)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
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
                                        }
                                ) {
                                    Text(
                                        text = "${currentSpeed}X",
                                        color = Color.White,
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
                                    value = currentPos.toFloat(),
                                    onValueChange = { targetPos ->
                                        currentPos = targetPos.toLong()
                                        player.seekTo(currentPos)
                                    },
                                    valueRange = 0f..totalDuration.toFloat(),
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

                                IconButton(onClick = {
                                    val curIdx = queue.indexOfFirst { it.id == video.id }
                                    if (curIdx > 0) onSelectVideo(queue[curIdx - 1])
                                }) {
                                    CineSkipIcon(isNext = false, tint = Color.White)
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

                                IconButton(onClick = {
                                    val curIdx = queue.indexOfFirst { it.id == video.id }
                                    if (curIdx >= 0 && curIdx < queue.lastIndex) onSelectVideo(queue[curIdx + 1])
                                }) {
                                    CineSkipIcon(isNext = true, tint = Color.White)
                                }

                                IconButton(onClick = {
                                    player.seekTo((player.currentPosition + 10000).coerceAtMost(totalDuration))
                                }) {
                                    CineSeekArcIcon(isForward = true, seconds = 10, tint = Color.White)
                                }

                                Text(
                                    text = "Speed",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier
                                        .clickable {
                                            currentSpeed = if (currentSpeed == 1.0f) 1.5f else 1.0f
                                            player.playbackParameters = PlaybackParameters(currentSpeed)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )

                                IconButton(onClick = {
                                    currentResizeMode = when (currentResizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
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
            if (isDelayWidgetVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                ) {
                    SubtitleDelayWidget(
                        subtitleState = subtitleState,
                        onClose = { isDelayWidgetVisible = false }
                    )
                }
            }

            // --- SUBTITLE SUITE MODALS ---
            when (activeSubtitleModal) {
                "HUB" -> {
                    SubtitleHubSheet(
                        subtitleState = subtitleState,
                        onOpenFile = {
                            activeSubtitleModal = null
                            bridge.pickFiles("*/*", false) { uris ->
                                val pickedUri = uris.firstOrNull() ?: return@pickFiles
                                try {
                                    context.contentResolver.openInputStream(pickedUri)?.bufferedReader()?.use { reader ->
                                        val content = reader.readText()
                                        val fileName = pickedUri.lastPathSegment ?: "Local Subtitle.srt"
                                        subtitleState.loadSrtText(content, fileName)
                                        bridge.showToast("Loaded: $fileName")
                                    }
                                } catch (e: Exception) {
                                    bridge.showToast("Error reading file: ${e.message}")
                                }
                            }
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
                        onClose = { activeSubtitleModal = null }
                    )
                }
                "STYLE" -> {
                    SubtitleStyleSheet(
                        subtitleState = subtitleState,
                        onBack = { activeSubtitleModal = "HUB" }
                    )
                }
                "DOWNLOAD" -> {
                    OnlineSubtitleSearchModal(
                        videoName = video.name,
                        onSelectSubtitle = { srtText, label ->
                            subtitleState.loadSrtText(srtText, label)
                            bridge.showToast("Subtitles loaded: $label")
                            activeSubtitleModal = null
                        },
                        onClose = { activeSubtitleModal = "HUB" }
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
    fun OnlineSubtitleSearchModal(
        videoName: String,
        onSelectSubtitle: (String, String) -> Unit,
        onClose: () -> Unit
    ) {
        var queryText by remember { mutableStateOf(OpenSubtitlesService.cleanTitle(videoName)) }
        var isSearching by remember { mutableStateOf(false) }
        var isDownloadingId by remember { mutableStateOf<Long?>(null) }
        var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
        val scope = rememberCoroutineScope()

        fun triggerSearch() {
            if (queryText.isBlank()) return
            scope.launch {
                isSearching = true
                results = OpenSubtitlesService.search(queryText.trim(), "en")
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
                        } else if (results.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No subtitles found", color = Color(0xFF8B949E), fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                                scope.launch {
                                                    val srtText = OpenSubtitlesService.downloadSubtitleText(item.fileId)
                                                    isDownloadingId = null
                                                    if (srtText != null) {
                                                        onSelectSubtitle(srtText, "${item.language.uppercase()} - ${item.releaseName.take(30)}")
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
            },
            confirmButton = {}
        )
    }
}