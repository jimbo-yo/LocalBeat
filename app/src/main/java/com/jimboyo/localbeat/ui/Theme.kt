package com.jimboyo.localbeat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.jimboyo.localbeat.AppFont
import com.jimboyo.localbeat.R
import com.jimboyo.localbeat.ThemeMode

@OptIn(ExperimentalTextApi::class)
fun fontFamily(font: AppFont): FontFamily {
    val res = when (font) {
        AppFont.FIGTREE -> R.font.figtree
        AppFont.INTER -> R.font.inter
        AppFont.NUNITO_SANS -> R.font.nunito_sans
        AppFont.ATKINSON -> R.font.atkinson
    }
    // The bundled fonts are variable fonts: one file, any weight.
    return FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
            Font(res, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
        }
    )
}

private fun typography(family: FontFamily): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = family),
        displayMedium = d.displayMedium.copy(fontFamily = family),
        displaySmall = d.displaySmall.copy(fontFamily = family),
        headlineLarge = d.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineMedium = d.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        headlineSmall = d.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleLarge = d.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        bodyLarge = d.bodyLarge.copy(fontFamily = family),
        bodyMedium = d.bodyMedium.copy(fontFamily = family),
        bodySmall = d.bodySmall.copy(fontFamily = family),
        labelLarge = d.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = d.labelMedium.copy(fontFamily = family),
        labelSmall = d.labelSmall.copy(fontFamily = family),
    )
}

/** Material You colours from the wallpaper, in the chosen light/dark mode and font. */
@Composable
fun LocalBeatTheme(mode: ThemeMode, font: AppFont, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val type = remember(font) { typography(fontFamily(font)) }
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
