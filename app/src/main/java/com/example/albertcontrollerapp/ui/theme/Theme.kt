package com.example.albertcontrollerapp.ui.theme


import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colori presi da themecolor.conf

@Composable
fun AlbertTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AlbertCyan,
            onPrimary = AlbertOnCyan,
            background = AlbertBg,
            onBackground = AlbertText,
            surface = AlbertPanel,
            onSurface = AlbertText,
            surfaceVariant = AlbertPanel2,
            onSurfaceVariant = AlbertMute,
            outline = AlbertLine,
            error = AlbertRed,
            onError = AlbertOnRed,
        ),
        content = content,
    )
}