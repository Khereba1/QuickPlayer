package com.myplayer.app

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlin.math.abs

data class AccentDef(val nameRes: Int, val hue: Float)

val Accents = listOf(
    AccentDef(R.string.accent_system, 243f),
    AccentDef(R.string.accent_indigo, 243f),
    AccentDef(R.string.accent_teal, 174f),
    AccentDef(R.string.accent_rose, 340f),
    AccentDef(R.string.accent_orange, 24f),
    AccentDef(R.string.accent_green, 140f),
    AccentDef(R.string.accent_blue, 208f),
)

fun hslColor(h: Float, s: Float, l: Float): Color {
    var hue = h % 360f
    if (hue < 0f) hue += 360f
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = hue / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val r1: Float
    val g1: Float
    val b1: Float
    if (hp < 1f) {
        r1 = c; g1 = x; b1 = 0f
    } else if (hp < 2f) {
        r1 = x; g1 = c; b1 = 0f
    } else if (hp < 3f) {
        r1 = 0f; g1 = c; b1 = x
    } else if (hp < 4f) {
        r1 = 0f; g1 = x; b1 = c
    } else if (hp < 5f) {
        r1 = x; g1 = 0f; b1 = c
    } else {
        r1 = c; g1 = 0f; b1 = x
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}

fun accentPreview(index: Int): Color {
    val i = index.coerceIn(0, Accents.lastIndex)
    return hslColor(Accents[i].hue, 0.7f, 0.52f)
}

private fun buildScheme(context: Context, dark: Boolean, accent: Int, amoled: Boolean): ColorScheme {
    val dynamic = accent == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme: ColorScheme = if (dynamic) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val hue = Accents[accent.coerceIn(0, Accents.lastIndex)].hue
        if (dark) {
            darkColorScheme(
                primary = hslColor(hue, 0.85f, 0.76f),
                onPrimary = hslColor(hue, 0.5f, 0.14f),
                primaryContainer = hslColor(hue, 0.45f, 0.27f),
                onPrimaryContainer = hslColor(hue, 0.9f, 0.9f),
                secondary = hslColor(hue + 40f, 0.55f, 0.72f),
                onSecondary = hslColor(hue + 40f, 0.5f, 0.14f),
                background = Color(0xFF0D1117),
                onBackground = Color(0xFFE6EDF3),
                surface = Color(0xFF12171E),
                onSurface = Color(0xFFE6EDF3),
                surfaceVariant = Color(0xFF212833),
                onSurfaceVariant = Color(0xFF9AA4B2),
                outline = Color(0xFF3A4350),
                surfaceContainerLowest = Color(0xFF0A0D12),
                surfaceContainerLow = Color(0xFF12171E),
                surfaceContainer = Color(0xFF171D25),
                surfaceContainerHigh = Color(0xFF1D242E),
                surfaceContainerHighest = Color(0xFF242C38),
            )
        } else {
            lightColorScheme(
                primary = hslColor(hue, 0.62f, 0.44f),
                onPrimary = Color.White,
                primaryContainer = hslColor(hue, 0.85f, 0.91f),
                onPrimaryContainer = hslColor(hue, 0.6f, 0.15f),
                secondary = hslColor(hue + 40f, 0.5f, 0.38f),
                onSecondary = Color.White,
                background = Color(0xFFF6F7FB),
                onBackground = Color(0xFF1B1F2A),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1B1F2A),
                surfaceVariant = Color(0xFFE8EAF3),
                onSurfaceVariant = Color(0xFF5D6577),
                outline = Color(0xFFC9CEDB),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF9FAFD),
                surfaceContainer = Color(0xFFF0F2F8),
                surfaceContainerHigh = Color(0xFFE9ECF4),
                surfaceContainerHighest = Color(0xFFE2E6F0),
            )
        }
    }
    if (dark && amoled) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color(0xFF050505),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF080808),
            surfaceContainer = Color(0xFF0D0D0F),
            surfaceContainerHigh = Color(0xFF131316),
            surfaceContainerHighest = Color(0xFF1A1A1E),
        )
    }
    return scheme
}

@Composable
fun AppTheme(dark: Boolean, accent: Int, amoled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(dark, accent, amoled) { buildScheme(context, dark, accent, amoled) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colors.background.toArgb()
                window.navigationBarColor = colors.background.toArgb()
                window.isNavigationBarContrastEnforced = false
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
