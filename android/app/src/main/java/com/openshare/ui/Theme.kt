package com.openshare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SBPalette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val card: Color,
    val border: Color,
    val muted: Color,
    val text: Color,
)

private val LightPalette = SBPalette(
    bg = Color(0xFFF1F5F9),
    surface = Color(0xFFF8FAFC),
    surfaceAlt = Color(0xFFF1F5F9),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFE2E8F0),
    muted = Color(0xFF64748B),
    text = Color(0xFF0F172A),
)

private val DarkPalette = SBPalette(
    bg = Color(0xFF0B1220),
    surface = Color(0xFF0F172A),
    surfaceAlt = Color(0xFF1E293B),
    card = Color(0xFF111827),
    border = Color(0xFF334155),
    muted = Color(0xFF94A3B8),
    text = Color(0xFFE5E7EB),
)

val LocalSBPalette = staticCompositionLocalOf { LightPalette }

object SBColors {
    val Primary = Color(0xFF2563EB)
    val PrimaryDark = Color(0xFF1D4ED8)
    val Secondary = Color(0xFF7C3AED)
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFDC2626)

    val Bg: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.surface
    val SurfaceAlt: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.surfaceAlt
    val Card: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.card
    val Border: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.border
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.muted
    val Text: Color @Composable @ReadOnlyComposable get() = LocalSBPalette.current.text
}

@Composable
fun OpenShareTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = SBColors.Primary,
            onPrimary = Color.White,
            secondary = SBColors.Secondary,
            tertiary = SBColors.Success,
            background = palette.surface,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = SBColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = SBColors.Primary,
            onPrimary = Color.White,
            secondary = SBColors.Secondary,
            tertiary = SBColors.Success,
            background = palette.surface,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = SBColors.Danger,
        )
    }
    CompositionLocalProvider(LocalSBPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            ),
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp)
            ),
            content = content
        )
    }
}

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    val kb = 1024.0; val mb = kb * 1024.0; val gb = mb * 1024.0
    return when {
        b >= gb -> String.format("%.2f GB", b / gb)
        b >= mb -> String.format("%.2f MB", b / mb)
        b >= kb -> String.format("%.1f KB", b / kb)
        else -> "${b.toLong()} B"
    }
}

fun stateColor(state: String): Color = when (state) {
    "idle" -> Color(0xFF64748B)
    "discovering", "pairing", "awaiting_accept", "awaitingAccept" -> SBColors.Warning
    "transferring", "verifying" -> SBColors.Primary
    "complete" -> SBColors.Success
    "failed", "cancelled" -> SBColors.Danger
    else -> Color(0xFF64748B)
}
