package com.jimboyo.localbeat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.jimboyo.localbeat.ColorStyle
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

/**
 * "Prism", the LocalBeat suite's look (same as LocalBeat Studio on the
 * desktop): ink-dark surfaces and one signature accent, the prism gradient,
 * like light on the data side of a CD. Keep in step with CDRipPipeline
 * `app/gui/src/theme.rs`.
 */
object Prism {
    val cyan = Color(0xFF3EE0FF)
    val blue = Color(0xFF5B8CFF)
    val violet = Color(0xFFA06BFF)
    val magenta = Color(0xFFFF5FA8)
    val amber = Color(0xFFFFB547)
    val accent = Color(0xFF8476FF)
    val ok = Color(0xFF3DDC97)
    val brandGold = Color(0xFFF2B33D)
    val brandNavy = Color(0xFF1B2A3A)

    /** The gradient, left to right. */
    val gradient = Brush.horizontalGradient(listOf(cyan, blue, violet, magenta, amber))

    val dark = darkColorScheme(
        primary = accent, onPrimary = Color.White,
        primaryContainer = Color(0xFF2E2A66), onPrimaryContainer = Color(0xFFDAD5FF),
        secondary = blue, onSecondary = Color.White,
        secondaryContainer = Color(0xFF232C4D), onSecondaryContainer = Color(0xFFD6E2FF),
        tertiary = magenta, onTertiary = Color.White,
        tertiaryContainer = Color(0xFF4A1D35), onTertiaryContainer = Color(0xFFFFD8E8),
        background = Color(0xFF10121C), onBackground = Color(0xFFECEEF7),
        surface = Color(0xFF10121C), onSurface = Color(0xFFECEEF7),
        // Not exactly surfaceContainerHigh: Material picks a panel's text colour by
        // matching its colour, and surfaceVariant (muted text) would win.
        surfaceVariant = Color(0xFF22263A), onSurfaceVariant = Color(0xFF9AA0B8),
        surfaceContainerLowest = Color(0xFF0B0C14), surfaceContainerLow = Color(0xFF141624),
        surfaceContainer = Color(0xFF171A28), surfaceContainerHigh = Color(0xFF1F2335),
        surfaceContainerHighest = Color(0xFF262B40),
        surfaceTint = accent, inverseSurface = Color(0xFFECEEF7), inverseOnSurface = Color(0xFF141625),
        inversePrimary = Color(0xFF5B4BFF),
        outline = Color(0xFF646A85), outlineVariant = Color(0xFF2A2F45),
        error = Color(0xFFFF6B6B), onError = Color.White,
        errorContainer = Color(0xFF4D1F24), onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color.Black,
    )

    val light = lightColorScheme(
        primary = Color(0xFF5B4BFF), onPrimary = Color.White,
        primaryContainer = Color(0xFFE6E2FF), onPrimaryContainer = Color(0xFF1C1260),
        secondary = Color(0xFF3D6FE8), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDE6FF), onSecondaryContainer = Color(0xFF0F2350),
        tertiary = Color(0xFFD9408A), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDCEA), onTertiaryContainer = Color(0xFF4A0F2C),
        background = Color(0xFFF8F8FC), onBackground = Color(0xFF141625),
        surface = Color(0xFFF8F8FC), onSurface = Color(0xFF141625),
        surfaceVariant = Color(0xFFE9EAF3), onSurfaceVariant = Color(0xFF5C617A),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F2F9),
        surfaceContainer = Color(0xFFEEEEF6), surfaceContainerHigh = Color(0xFFE8E8F2),
        surfaceContainerHighest = Color(0xFFE2E3EE),
        surfaceTint = Color(0xFF5B4BFF), inverseSurface = Color(0xFF242638), inverseOnSurface = Color(0xFFF1F1F8),
        inversePrimary = accent,
        outline = Color(0xFF9296AC), outlineVariant = Color(0xFFDEE0EC),
        error = Color(0xFFD93A3A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        scrim = Color.Black,
    )
}

/** Prism or Material You colours, in the chosen light/dark mode and font. */
@Composable
fun LocalBeatTheme(mode: ThemeMode, style: ColorStyle, font: AppFont, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when (style) {
        ColorStyle.PRISM -> if (dark) Prism.dark else Prism.light
        ColorStyle.WALLPAPER -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val type = remember(font) { typography(fontFamily(font)) }
    androidx.compose.runtime.CompositionLocalProvider(LocalPrism provides (style == ColorStyle.PRISM)) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}
