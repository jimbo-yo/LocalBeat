package com.jimboyo.localbeat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jimboyo.localbeat.library.Album
import com.jimboyo.localbeat.library.Origin
import com.jimboyo.localbeat.library.Track

/**
 * A small coloured label, like LocalBeat Studio's badges: the colour tinted
 * behind, a thin border and the text in the colour itself (darker on a light
 * theme, so it stays readable).
 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val shape = RoundedCornerShape(5.dp)
    Text(
        text.uppercase(), maxLines = 1,
        color = if (dark) color else lerp(color, Color.Black, 0.4f),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        modifier = modifier
            .background(color.copy(alpha = 0.16f), shape)
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Each source its own colour: CD rips green (as in Studio), older copies by type. */
fun originColor(origin: Origin): Color = when (origin) {
    Origin.CdRip -> Prism.ok
    is Origin.OldCopy -> when (origin.format) {
        "MP3" -> Prism.amber
        "AAC" -> Prism.magenta
        "FLAC" -> Prism.cyan
        else -> Prism.violet
    }
    Origin.Unknown -> Color.Gray
}

fun originText(origin: Origin): String? = when (origin) {
    Origin.CdRip -> "CD rip"
    is Origin.OldCopy -> if (origin.format.isEmpty()) "Old copy" else "Old ${origin.format}"
    Origin.Unknown -> null
}

/**
 * Where songs came from and what plays: "CD RIP" (green) or "OLD MP3" (amber),
 * "OLD AAC" (magenta)…; "ON THE SERVER" (violet-blue) for server songs; then
 * the file type that plays, in grey ("OPUS").
 */
@Composable
fun SourcePills(origin: Origin, onServer: Boolean, playedAs: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        originText(origin)?.let { Pill(it, originColor(origin)) }
        if (onServer) Pill("On the server", Prism.accent)
        if (playedAs.isNotEmpty()) Pill(playedAs, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SourcePills(track: Track, modifier: Modifier = Modifier) =
    SourcePills(track.origin, track.onServer, track.playedAs, modifier)

/** An album's pills: each one only when all its songs agree. */
@Composable
fun SourcePills(album: Album, modifier: Modifier = Modifier) {
    val t = album.tracks
    SourcePills(
        t.map { it.origin }.distinct().singleOrNull() ?: Origin.Unknown,
        t.isNotEmpty() && t.all { it.onServer },
        t.map { it.playedAs }.distinct().singleOrNull().orEmpty(),
        modifier,
    )
}
