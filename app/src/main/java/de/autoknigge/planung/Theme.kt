package de.autoknigge.planung

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Markenfarben der autoknigge-Apps: Tannengrün mit Gold.
val Forest = Color(0xFF0B2A21)
val Panel = Color(0xFF123A2E)
val Panel2 = Color(0xFF1B4C3C)
val Gold = Color(0xFFE3B341)
val Ink = Color(0xFFEAF2EE)
val Muted = Color(0xFFA4BDB2)
val Line = Color(0xFF2B5848)

@Composable
fun PlanungTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            onPrimary = Color(0xFF1A1400),
            secondary = Panel2,
            background = Forest,
            surface = Panel,
            onBackground = Ink,
            onSurface = Ink,
            surfaceVariant = Panel2,
            onSurfaceVariant = Muted,
            outline = Line
        ),
        content = content
    )
}
