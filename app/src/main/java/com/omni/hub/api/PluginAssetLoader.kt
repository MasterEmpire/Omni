package com.omni.hub.api

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File

/**
 * Dynamic Asset helper for dynamic Compose plugins to load resources from plugin_res/.
 */
object PluginAssetLoader {

    fun loadBitmap(baseDir: String, relativePath: String): ImageBitmap? {
        val file = File(baseDir, relativePath)
        return if (file.exists() && file.isFile) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } else null
    }

    fun loadText(baseDir: String, relativePath: String): String? {
        val file = File(baseDir, relativePath)
        return if (file.exists() && file.isFile) {
            try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else null
    }
}

@Composable
fun PluginImage(
    baseDir: String,
    relativePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null
) {
    val bitmap = remember(baseDir, relativePath) {
        PluginAssetLoader.loadBitmap(baseDir, relativePath)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(modifier = modifier)
    }
}