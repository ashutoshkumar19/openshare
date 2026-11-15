package com.openshare.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

object SBColors {
    val Primary = Color(0xFF2563EB)
    val PrimaryDark = Color(0xFF1D4ED8)
    val Secondary = Color(0xFF7C3AED)
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFDC2626)
    val Bg = Color(0xFFF1F5F9)
    val Surface = Color(0xFFF8FAFC)
    val SurfaceAlt = Color(0xFFF1F5F9)
    val Card = Color(0xFFFFFFFF)
    val Border = Color(0xFFE2E8F0)
    val Muted = Color(0xFF64748B)
    val Text = Color(0xFF0F172A)
}

@Composable
fun OpenShareTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = SBColors.Primary,
        onPrimary = Color.White,
        secondary = SBColors.Secondary,
        tertiary = SBColors.Success,
        background = SBColors.Surface,
        surface = SBColors.Card,
        onSurface = SBColors.Text,
        surfaceVariant = Color(0xFFF1F5F9),
        onSurfaceVariant = SBColors.Muted,
        outline = SBColors.Border,
        error = SBColors.Danger,
    )
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
    "idle" -> SBColors.Muted
    "discovering", "pairing", "awaiting_accept", "awaitingAccept" -> SBColors.Warning
    "transferring", "verifying" -> SBColors.Primary
    "complete" -> SBColors.Success
    "failed", "cancelled" -> SBColors.Danger
    else -> SBColors.Muted
}
