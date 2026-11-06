package com.jimboyo.localbeat.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.jimboyo.localbeat.LocalBeatApp
import com.jimboyo.localbeat.library.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ArtMemory : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** An album cover, or a placeholder for albums without one (the older rips). */
@Composable
fun AlbumArt(album: Album?, modifier: Modifier = Modifier, maxPx: Int = 512) {
    val context = LocalContext.current
    val key = album?.let { "${it.id}/$maxPx" }
    val bitmap by produceState(key?.let { ArtMemory.get(it) }, key) {
        if (album == null || key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val file = (context.applicationContext as LocalBeatApp).art.file(album) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { ArtMemory.put(key, it) }
        }
    }
    val shaped = modifier.clip(RoundedCornerShape(6.dp))
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = shaped, contentScale = ContentScale.Crop)
    } else {
        Box(shaped.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Album, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.5f),
            )
        }
    }
}
