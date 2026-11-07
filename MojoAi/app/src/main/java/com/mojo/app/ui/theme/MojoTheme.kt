package com.mojo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** "Midnight Aurora" — the colors from res/values/colors.xml, for Compose. */
object Aurora {
    val Bg = Color(0xFF060A18)
    val Surface1 = Color(0xFF0A1024)
    val Card = Color(0xFF0E1530)
    val Border = Color(0xFF1C2647)
    val Cyan = Color(0xFF38E1FF)
    val CyanDark = Color(0xFF14A9C9)
    val OnCyan = Color(0xFF04121F)
    val Violet = Color(0xFF8B5CF6)
    val Text = Color(0xFFEAF2FF)
    val TextSecondary = Color(0xFF9FB0D6)
    val TextMuted = Color(0xFF5E6B91)
    val Green = Color(0xFF2EE6A6)
    val WarningBg = Color(0xFF241A08)
    val WarningText = Color(0xFFFFC86B)
}

@Composable
fun MojoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aurora.Cyan,
            onPrimary = Aurora.OnCyan,
            secondary = Aurora.Violet,
            background = Aurora.Bg,
            onBackground = Aurora.Text,
            surface = Aurora.Card,
            onSurface = Aurora.Text,
            outline = Aurora.Border,
        ),
        content = content,
    )
}
