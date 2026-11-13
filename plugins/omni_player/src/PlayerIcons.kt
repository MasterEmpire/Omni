package com.omni.plugin.player

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun vectorIcon(name: String, pathData: String): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.White)
    ).build()
}

object PlayerIcons {
    val Lock: ImageVector by lazy {
        vectorIcon(
            "Lock",
            "M6 8V7C6 3.68629 8.68629 1 12 1C15.3137 1 18 3.68629 18 7V8H20C20.5523 8 21 8.44772 21 9V21C21 21.5523 20.5523 22 20 22H4C3.44772 22 3 21.5523 3 21V9C3 8.44772 3.44772 8 4 8H6ZM19 10H5V20H19V10ZM11 15.7324C10.4022 15.3866 10 14.7403 10 14C10 12.8954 10.8954 12 12 12C13.1046 12 14 12.8954 14 14C14 14.7403 13.5978 15.3866 13 15.7324V18H11V15.7324ZM8 8H16V7C16 4.79086 14.2091 3 12 3C9.79086 3 8 4.79086 8 7V8Z"
        )
    }

    val LockOpen: ImageVector by lazy {
        vectorIcon(
            "LockOpen",
            "M7 10H20C20.5523 10 21 10.4477 21 11V21C21 21.5523 20.5523 22 20 22H4C3.44772 22 3 21.5523 3 21V11C3 10.4477 3.44772 10 4 10H5V9C5 5.13401 8.13401 2 12 2C14.7405 2 17.1131 3.5748 18.2624 5.86882L16.4731 6.76344C15.6522 5.12486 13.9575 4 12 4C9.23858 4 7 6.23858 7 9V10ZM5 12V20H19V12H5ZM10 15H14V17H10V15Z"
        )
    }

    val Camera: ImageVector by lazy {
        vectorIcon(
            "Camera",
            "M9.82843 5L7.82843 7H4V19H20V7H16.1716L14.1716 5H9.82843ZM9 3H15L17 5H21C21.5523 5 22 5.44772 22 6V20C22 20.5523 21.5523 21 21 21H3C2.44772 21 2 20.5523 2 20V6C2 5.44772 2.44772 5 3 5H7L9 3ZM12 18C8.96243 18 6.5 15.5376 6.5 12.5C6.5 9.46243 8.96243 7 12 7C15.0376 7 17.5 9.46243 17.5 12.5C17.5 15.5376 15.0376 18 12 18ZM12 16C13.933 16 15.5 14.433 15.5 12.5C15.5 10.567 13.933 9 12 9C10.067 9 8.5 10.567 8.5 12.5C8.5 14.433 10.067 16 12 16Z"
        )
    }

    val PictureInPicture: ImageVector by lazy {
        vectorIcon(
            "PictureInPicture",
            "M21 3C21.5523 3 22 3.44772 22 4V11H20V5H4V19H10V21H3C2.44772 21 2 20.5523 2 20V4C2 3.44772 2.44772 3 3 3H21ZM21 13C21.5523 13 22 13.4477 22 14V20C22 20.5523 21.5523 21 21 21H13C12.4477 21 12 20.5523 12 20V14C12 13.4477 12.4477 13 13 13H21ZM20 15H14V19H20V15ZM6.70711 6.29289L8.95689 8.54289L11 6.5V12H5.5L7.54289 9.95689L5.29289 7.70711L6.70711 6.29289Z"
        )
    }

    val ScreenRotation: ImageVector by lazy {
        vectorIcon(
            "ScreenRotation",
            "M7.5 21.5c-3.25-1.56-5.59-4.74-5.95-8.5H.05C.56 19.16 5.71 24 12 24l.66-.03l-3.81-3.81m5.98 1.03L2.81 9.17l6.36-6.36l12.02 12.02M10.23 1.75a1.49 1.49 0 0 0-2.12 0L1.75 8.11a1.49 1.49 0 0 0 0 2.12l12.02 12.02c.59.59 1.54.59 2.12 0l6.36-6.36c.59-.59.59-1.54 0-2.12zm6.27.75c3.25 1.57 5.59 4.74 5.95 8.5h1.5C23.44 4.84 18.29 0 12 0l-.66.03l3.81 3.81z"
        )
    }

    val Headphones: ImageVector by lazy {
        vectorIcon(
            "Headphones",
            "M12 4C7.58172 4 4 7.58172 4 12H7C8.10457 12 9 12.8954 9 14V19C9 20.1046 8.10457 21 7 21H4C2.89543 21 2 20.1046 2 19V12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12V19C22 20.1046 21.1046 21 20 21H17C15.8954 21 15 20.1046 15 19V14C15 12.8954 15.8954 12 17 12H20C20 7.58172 16.4183 4 12 4ZM4 14V19H7V14H4ZM17 14V19H20V14H17Z"
        )
    }

    val VolumeUp: ImageVector by lazy {
        vectorIcon(
            "VolumeUp",
            "M12.43 4.1a1 1 0 0 0-1 .12L6.65 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h3.65l4.73 3.78A1 1 0 0 0 12 20a.9.9 0 0 0 .43-.1A1 1 0 0 0 13 19V5a1 1 0 0 0-.57-.9M11 16.92l-3.38-2.7A1 1 0 0 0 7 14H4v-4h3a1 1 0 0 0 .62-.22L11 7.08Zm4.14-12.83a1 1 0 1 0-.28 2a6 6 0 0 1 0 11.86a1 1 0 0 0 .14 2h.14a8 8 0 0 0 0-15.82Zm-.46 9.78a1 1 0 0 0 .32 2a1.1 1.1 0 0 0 .32-.05a4 4 0 0 0 0-7.54a1 1 0 0 0-.64 1.9a2 2 0 0 1 0 3.74Z"
        )
    }

    val VolumeMute: ImageVector by lazy {
        vectorIcon(
            "VolumeMute",
            "M12.43 4.1a1 1 0 0 0-1 .12L6.65 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h3.65l4.73 3.78A1 1 0 0 0 12 20a.9.9 0 0 0 .43-.1A1 1 0 0 0 13 19V5a1 1 0 0 0-.57-.9M11 16.92l-3.38-2.7A1 1 0 0 0 7 14H4v-4h3a1 1 0 0 0 .62-.22L11 7.08ZM19.91 12l1.8-1.79a1 1 0 0 0-1.42-1.42l-1.79 1.8l-1.79-1.8a1 1 0 0 0-1.42 1.42l1.8 1.79l-1.8 1.79a1 1 0 0 0 0 1.42a1 1 0 0 0 1.42 0l1.79-1.8l1.79 1.8a1 1 0 0 0 1.42 0a1 1 0 0 0 0-1.42Z"
        )
    }

    val Replay10: ImageVector by lazy {
        vectorIcon(
            "Replay10",
            "M12 2C17.5228 2 22 6.47715 22 12 22 17.5228 17.5228 22 12 22 6.47715 22 2 17.5228 2 12H4C4 16.4183 7.58172 20 12 20 16.4183 20 20 16.4183 20 12 20 7.58172 16.4183 4 12 4 9.25022 4 6.82447 5.38734 5.38451 7.50024L8 7.5V9.5H2V3.5H4L3.99989 5.99918C5.82434 3.57075 8.72873 2 12 2ZM15.5 13.25C15.5 13.8023 15.0523 14.25 14.5 14.25 13.9477 14.25 13.5 13.8023 13.5 13.25V10.75C13.5 10.1977 13.9477 9.75 14.5 9.75 15.0523 9.75 15.5 10.1977 15.5 10.75V13.25ZM14.5 8.25C13.1193 8.25 12 9.36929 12 10.75V13.25C12 14.6307 13.1193 15.75 14.5 15.75 15.8807 15.75 17 14.6307 17 13.25V10.75C17 9.36929 15.8807 8.25 14.5 8.25ZM8.5 15.5V8.5H10V15.5H8.5Z"
        )
    }

    val Forward10: ImageVector by lazy {
        vectorIcon(
            "Forward10",
            "M12 2C6.47715 2 2 6.47715 2 12 2 17.5228 6.47715 22 12 22 17.5228 22 22 17.5228 22 12H20C20 16.4183 16.4183 20 12 20 7.58172 20 4 16.4183 4 12 4 7.58172 7.58172 4 12 4 14.7498 4 17.1755 5.38734 18.6155 7.50024L16 7.5V8.74982C15.5822 8.43597 15.0628 8.25 14.5 8.25 13.1193 8.25 12 9.36929 12 10.75V13.25C12 14.6307 13.1193 15.75 14.5 15.75 15.8807 15.75 17 14.6307 17 13.25V10.75C17 10.2946 16.8783 9.86772 16.6655 9.5H22V3.5H20L20.0001 5.99918C18.1757 3.57075 15.2713 2 12 2ZM15.5 10.75V13.25C15.5 13.8023 15.0523 14.25 14.5 14.25 13.9477 14.25 13.5 13.8023 13.5 13.25V10.75C13.5 10.1977 13.9477 9.75 14.5 9.75 15.0523 9.75 15.5 10.1977 15.5 10.75ZM10 8.5H8.5V15.5H10V8.5Z"
        )
    }

    val AspectRatio: ImageVector by lazy {
        vectorIcon(
            "AspectRatio",
            "M8 3V5H4V9H2V3H8ZM2 21V15H4V19H8V21H2ZM22 21H16V19H20V15H22V21ZM22 9H20V5H16V3H22V9Z"
        )
    }

    val Play: ImageVector by lazy {
        vectorIcon(
            "Play",
            "M5.669 4.76a1.47 1.47 0 0 1 2.04-1.177c1.062.453 3.442 1.532 6.462 3.276c3.021 1.744 5.146 3.266 6.069 3.958c.788.59.79 1.763.001 2.355c-.914.687-3.013 2.191-6.07 3.956c-3.06 1.766-5.412 2.832-6.464 3.28a1.467 1.467 0 0 1-2.038-1.177c-.138-1.141-.396-3.734-.396-7.236c0-3.5.257-6.092.396-7.235"
        )
    }

    val Pause: ImageVector by lazy {
        vectorIcon(
            "Pause",
            "M15 7C15 6.44772 15.4477 6 16 6C16.5523 6 17 6.44772 17 7V17C17 17.5523 16.5523 18 16 18C15.4477 18 15 17.5523 15 17V7ZM7 7C7 6.44772 7.44772 6 8 6C8.55228 6 9 6.44772 9 7V17C9 17.5523 8.55228 18 8 18C7.44772 18 7 17.5523 7 17V7Z"
        )
    }

    val SkipPrevious: ImageVector by lazy {
        vectorIcon(
            "SkipPrevious",
            "M7 4a1 1 0 0 1 1 1v6.333l10.223-6.815a0.5 0.5 0 0 1 0.777 0.416v14.132a0.5 0.5 0 0 1-0.777 0.416L8 12.667V19a1 1 0 1 1-2 0V5a1 1 0 0 1 1-1m10 3.737L10.606 12L17 16.263z"
        )
    }

    val SkipNext: ImageVector by lazy {
        vectorIcon(
            "SkipNext",
            "M16 12.667L5.777 19.482A0.5 0.5 0 0 1 5 19.066V4.934a0.5 0.5 0 0 1 0.777-0.416L16 11.333V5a1 1 0 1 1 2 0v14a1 1 0 1 1-2 0zm-9-4.93v8.526L13.394 12z"
        )
    }

    val Playlist: ImageVector by lazy {
        vectorIcon(
            "Playlist",
            "M2 18H12V20H2V18ZM2 11H16V13H2V11ZM2 4H22V6H2V4ZM19 15.1707V9H24V11H21V18C21 19.6569 19.6569 21 18 21C16.3431 21 15 19.6569 15 18C15 16.3431 16.3431 15 18 15C18.3506 15 18.6872 15.0602 19 15.1707ZM18 19C18.5523 19 19 18.5523 19 18C19 17.4477 18.5523 17 18 17C17.4477 17 17 17.4477 17 18C17 18.5523 17.4477 19 18 19Z"
        )
    }

    val MoreVert: ImageVector by lazy {
        vectorIcon(
            "MoreVert",
            "M12 3C11.175 3 10.5 3.675 10.5 4.5C10.5 5.325 11.175 6 12 6C12.825 6 13.5 5.325 13.5 4.5C13.5 3.675 12.825 3 12 3ZM12 18C11.175 18 10.5 18.675 10.5 19.5C10.5 20.325 11.175 21 12 21C12.825 21 13.5 20.325 13.5 19.5C13.5 18.675 12.825 18 12 18ZM12 10.5C11.175 10.5 10.5 11.175 10.5 12C10.5 12.825 11.175 13.5 12 13.5C12.825 13.5 13.5 12.825 13.5 12C13.5 11.175 12.825 10.5 12 10.5Z"
        )
    }

    val Folder: ImageVector by lazy {
        vectorIcon(
            "Folder",
            "M4 5V19H20V7H11.5858L9.58579 5H4ZM12.4142 5H21C21.5523 5 22 5.44772 22 6V20C22 20.5523 21.5523 21 21 21H3C2.44772 21 2 20.5523 2 20V4C2 3.44772 2.44772 3 3 3H10.4142L12.4142 5Z"
        )
    }

    val Sun: ImageVector by lazy {
        vectorIcon(
            "Sun",
            "M12 18a6 6 0 1 1 0-12 6 6 0 0 1 0 12zm0-2a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM11 1h2v3h-2V1zm0 19h2v3h-2v-3zM3.515 4.929l1.414-1.414L7.05 5.636 5.636 7.05 3.515 4.93zM16.95 18.364l1.414-1.414 2.121 2.121-1.414 1.414-2.121-2.121zm2.121-14.85l1.414 1.415-2.121 2.121-1.414-1.414 2.121-2.121zM5.636 16.95l1.414 1.414-2.121 2.121-1.414-1.414 2.121-2.121zM23 11v2h-3v-2h3zM4 11v2H1v-2h3z"
        )
    }
}

@Composable
fun CineLockIcon(isLocked: Boolean, tint: Color = Color.White, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        imageVector = if (isLocked) PlayerIcons.Lock else PlayerIcons.LockOpen,
        contentDescription = if (isLocked) "Locked" else "Unlocked",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineCameraIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        imageVector = PlayerIcons.Camera,
        contentDescription = "Snapshot",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CinePipIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        imageVector = PlayerIcons.PictureInPicture,
        contentDescription = "Picture in Picture",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineRotateIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = PlayerIcons.ScreenRotation,
        contentDescription = "Rotate Screen",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineHeadphonesIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = PlayerIcons.Headphones,
        contentDescription = "Background Audio",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineVolumeIcon(isMuted: Boolean, tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = if (isMuted) PlayerIcons.VolumeMute else PlayerIcons.VolumeUp,
        contentDescription = if (isMuted) "Muted" else "Volume",
        tint = if (isMuted) Color(0xFFF85149) else tint,
        modifier = modifier
    )
}

@Composable
fun CineSeekArcIcon(isForward: Boolean, seconds: Int = 10, tint: Color = Color.White, modifier: Modifier = Modifier.size(26.dp)) {
    Icon(
        imageVector = if (isForward) PlayerIcons.Forward10 else PlayerIcons.Replay10,
        contentDescription = if (isForward) "Forward ${seconds}s" else "Replay ${seconds}s",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineAspectIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        imageVector = PlayerIcons.AspectRatio,
        contentDescription = "Aspect Ratio",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CinePlayPauseIcon(isPlaying: Boolean, tint: Color = Color.White, modifier: Modifier = Modifier.size(24.dp)) {
    Icon(
        imageVector = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
        contentDescription = if (isPlaying) "Pause" else "Play",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineSkipIcon(isNext: Boolean, tint: Color = Color.White, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(
        imageVector = if (isNext) PlayerIcons.SkipNext else PlayerIcons.SkipPrevious,
        contentDescription = if (isNext) "Next" else "Previous",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CinePlaylistIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = PlayerIcons.Playlist,
        contentDescription = "Playlist",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineMoreIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = PlayerIcons.MoreVert,
        contentDescription = "More",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineFolderIcon(tint: Color = Color(0xFF00E676), modifier: Modifier = Modifier.size(24.dp)) {
    Icon(
        imageVector = PlayerIcons.Folder,
        contentDescription = "Folder",
        tint = tint,
        modifier = modifier
    )
}

@Composable
fun CineSunIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(18.dp)) {
    Icon(
        imageVector = PlayerIcons.Sun,
        contentDescription = "Brightness",
        tint = tint,
        modifier = modifier
    )
}