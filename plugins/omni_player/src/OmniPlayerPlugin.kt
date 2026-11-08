package com.omni.plugin.player

import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

class OmniPlayerPlugin : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF000000),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFF22C55E)
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
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬", fontSize = 24.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Omni Player", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Device Video Folders", color = Color(0xFF8B949E), fontSize = 11.sp)
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

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF22C55E))
                }
            } else if (folders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📁", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("No video folders detected", color = Color(0xFF8B949E), fontSize = 14.sp)
                        Text("Ensure storage/video permissions are granted.", color = Color(0xFF484F58), fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(folders) { folder ->
                        FolderCard(folder = folder, onClick = { onOpenFolder(folder) })
                    }
                }
            }
        }
    }

    @Composable
    fun FolderCard(folder: VideoFolder, onClick: () -> Unit) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF21262D),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📁", fontSize = 24.sp)
                    }
                }

                Spacer(Modifier.width(12.dp))

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
                        text = "${folder.videoCount} videos • ${VideoRepository.formatFileSize(folder.totalSize)}",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF22C55E).copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "${folder.videoCount}",
                        color = Color(0xFF22C55E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

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
                .background(Color(0xFF0D1117))
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
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(folder.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${videos.size} videos in folder", color = Color(0xFF8B949E), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF22C55E))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(videos) { video ->
                        VideoThumbnailCard(
                            context = context,
                            video = video,
                            onClick = { onPlayVideo(video, videos) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun VideoThumbnailCard(
        context: Context,
        video: VideoItem,
        onClick: () -> Unit
    ) {
        var thumbnail by remember(video.id) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(video.id) {
            thumbnail = VideoRepository.getThumbnail(context, video.id, video.uri, video.path)
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color(0xFF000000)),
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
                        Text("🎞️", fontSize = 28.sp)
                    }

                    // Duration Tag (Bottom Right)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.75f),
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
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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

        var gestureOverlayText by remember { mutableStateOf<String?>(null) }
        var initialBrightness by remember { mutableFloatStateOf(0.5f) }
        var initialVolume by remember { mutableIntStateOf(50) }
        var isAudioOnlyMode by remember { mutableStateOf(false) }

        BackHandler {
            if (isLocked) {
                isLocked = false
            } else {
                onClosePlayer()
            }
        }

        // Initialize Player with Video
        LaunchedEffect(video.uri) {
            bridge.setImmersiveMode(true)
            val mediaItem = MediaItem.fromUri(video.uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            isPlaying = true
        }

        // Track Progress
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
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (player.duration > 0) totalDuration = player.duration
                    }
                }
            }
            player.addListener(listener)
            onDispose {
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
                .pointerInput(isLocked) {
                    if (!isLocked) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { offset ->
                                val width = size.width
                                when {
                                    offset.x < width * 0.35f -> {
                                        val target = (player.currentPosition - 10000).coerceAtLeast(0)
                                        player.seekTo(target)
                                        gestureOverlayText = "⏪ -10s"
                                    }
                                    offset.x > width * 0.65f -> {
                                        val target = (player.currentPosition + 10000).coerceAtMost(totalDuration)
                                        player.seekTo(target)
                                        gestureOverlayText = "⏩ +10s"
                                    }
                                    else -> {
                                        if (player.isPlaying) player.pause() else player.play()
                                    }
                                }
                            }
                        )
                    } else {
                        detectTapGestures(onTap = { showControls = !showControls })
                    }
                }
        ) {
            // 1. ExoPlayer Video Surface
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

            // Touch Gestures Overlay (Brightness on Left, Volume on Right)
            if (!isLocked) {
                AndroidView(
                    factory = { ctx ->
                        object : View(ctx) {
                            var downX = 0f
                            var downY = 0f
                            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                            val screenHeight = resources.displayMetrics.heightPixels.toFloat()

                            override fun onTouchEvent(ev: MotionEvent): Boolean {
                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = ev.rawX
                                        downY = ev.rawY
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val deltaY = downY - ev.rawY
                                        val percentDelta = (deltaY / screenHeight) * 100f
                                        if (downX < screenWidth * 0.45f) {
                                            // Brightness Drag
                                            val b = (50 + percentDelta).coerceIn(0f, 100f).toInt()
                                            bridge.setScreenBrightness(b)
                                            gestureOverlayText = "☀️ Brightness $b%"
                                        } else if (downX > screenWidth * 0.55f) {
                                            // Volume Drag
                                            val v = (50 + percentDelta).coerceIn(0f, 100f).toInt()
                                            bridge.setVolume("MEDIA", v)
                                            gestureOverlayText = "🔊 Volume $v%"
                                        }
                                    }
                                    MotionEvent.ACTION_UP,
                                    MotionEvent.ACTION_CANCEL -> {
                                        postDelayed({ gestureOverlayText = null }, 1200)
                                    }
                                }
                                return false // allow clicks to pass to Compose
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Gesture HUD Overlay
            if (gestureOverlayText != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = gestureOverlayText!!,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // Floating Screen Lock Button (Always visible on single tap)
            AnimatedVisibility(
                visible = showControls || isLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .clickable {
                            isLocked = !isLocked
                            bridge.showToast(if (isLocked) "🔒 Screen Locked" else "🔓 Screen Unlocked")
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (isLocked) "🔒" else "🔓", fontSize = 18.sp)
                    }
                }
            }

            // Floating Camera Screenshot Button
            AnimatedVisibility(
                visible = showControls && !isLocked,
                enter = fadeIn(),
                exit = fadeOut(),
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
                            bridge.showToast("📸 Frame snapshot captured!")
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📷", fontSize = 18.sp)
                    }
                }
            }

            // TOP & BOTTOM X-PLAYER CONTROL OVERLAYS
            AnimatedVisibility(
                visible = showControls && !isLocked,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // TOP BAR
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
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // PiP Button
                                IconButton(onClick = {
                                    val act = context as? android.app.Activity
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        act?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                                    }
                                }) {
                                    Text("⤢", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }

                                // HW/SW Decoder Button
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, Color.White),
                                    color = Color.Transparent,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text("HD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }

                                // Subtitles CC
                                Text("CC", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))

                                // Playlist Drawer
                                Text("☰", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))

                                // 3 Dots Menu
                                Text("⋮", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        }

                        // SUB-HEADER QUICK PILLS BAR
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                        ) {
                            // Orientation Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
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
                                Text("🔄", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }

                            // Mute Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isMuted = !isMuted
                                        player.volume = if (isMuted) 0f else 1f
                                    }
                            ) {
                                Text(if (isMuted) "🔇" else "🔊", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }

                            // Audio-Only Background Playback Mode (Headphones icon)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isAudioOnlyMode = !isAudioOnlyMode
                                        bridge.startMediaPlayback(video.name, "Omni Background Player", true) { playState ->
                                            if (playState) player.play() else player.pause()
                                        }
                                        bridge.showToast("🎧 Background Audio Mode Active!")
                                    }
                            ) {
                                Text("🎧", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }

                            // Playback Speed Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
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
                                Text("${currentSpeed}X", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }

                    // BOTTOM BAR CONTROLS
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        // Neon Green Timeline Scrubber
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = VideoRepository.formatDuration(currentPos),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
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
                                    activeTrackColor = Color(0xFF22C55E),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )

                            Text(
                                text = VideoRepository.formatDuration(totalDuration),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Lock Key
                            IconButton(onClick = { isLocked = true }) {
                                Text("🔒", fontSize = 18.sp)
                            }

                            // Seek -10s
                            IconButton(onClick = {
                                player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                            }) {
                                Text("⟲ 10", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Previous Track
                            IconButton(onClick = {
                                val curIdx = queue.indexOfFirst { it.id == video.id }
                                if (curIdx > 0) onSelectVideo(queue[curIdx - 1])
                            }) {
                                Text("|◀", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }

                            // Master Big Play/Pause Button
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (player.isPlaying) player.pause() else player.play()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 22.sp)
                                }
                            }

                            // Next Track
                            IconButton(onClick = {
                                val curIdx = queue.indexOfFirst { it.id == video.id }
                                if (curIdx >= 0 && curIdx < queue.lastIndex) onSelectVideo(queue[curIdx + 1])
                            }) {
                                Text("▶|", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }

                            // Seek +10s
                            IconButton(onClick = {
                                player.seekTo((player.currentPosition + 10000).coerceAtMost(totalDuration))
                            }) {
                                Text("10 ⟳", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Speed Label
                            Text(
                                text = "Speed",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier
                                    .clickable {
                                        currentSpeed = if (currentSpeed == 1.0f) 1.5f else 1.0f
                                        player.playbackParameters = PlaybackParameters(currentSpeed)
                                    }
                                    .padding(4.dp)
                            )

                            // Aspect Ratio Resize Toggle
                            IconButton(onClick = {
                                currentResizeMode = when (currentResizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }) {
                                Text("⛶", color = Color.White, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}