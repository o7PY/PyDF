package io.github.o7py.margin.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Fixed palette, no dynamic color (SPEC §12.2). Warm paper-like neutrals + one indigo accent. */
object MarginColors {
    val Accent = Color(0xFF3D5AFE)
    val ReaderBgLight = Color(0xFFE8E6E1)
    val ReaderBgDark = Color(0xFF1E1E1E)
}

private val Light = lightColorScheme(
    primary = MarginColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE6E3F3),
    onSecondaryContainer = Color(0xFF181A2C),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFBFAF6),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainer = Color(0xFFF2EFE9),
    surfaceContainerHigh = Color(0xFFECE9E2),
    surfaceContainerHighest = Color(0xFFE6E3DC),
    surfaceContainerLow = Color(0xFFF6F3ED),
    outline = Color(0xFF7B776E),
    outlineVariant = Color(0xFFCCC8BE),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF00218A),
    primaryContainer = Color(0xFF2A44D8),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    background = Color(0xFF151413),
    onBackground = Color(0xFFE6E2DC),
    surface = Color(0xFF1B1A19),
    onSurface = Color(0xFFE6E2DC),
    surfaceVariant = Color(0xFF34322E),
    onSurfaceVariant = Color(0xFFCDC8BE),
    surfaceContainer = Color(0xFF232220),
    surfaceContainerHigh = Color(0xFF2B2A27),
    surfaceContainerHighest = Color(0xFF363431),
    surfaceContainerLow = Color(0xFF1F1E1C),
    outline = Color(0xFF969187),
    outlineVariant = Color(0xFF4A4740),
)

val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun MarginTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val reduce = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalReduceMotion provides reduce, content = content)
    }
}
