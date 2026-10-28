package com.marvis.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MarvisBgTop = Color(0xFF05070D)
val MarvisBgBottom = Color(0xFF0C1224)
val MarvisSurface = Color(0xFF1A1F2E)

@Composable
fun MarvisTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF7C9CFF), background = MarvisBgTop, surface = MarvisSurface,
        onSurface = Color(0xFFE6E9F2), onBackground = Color(0xFFE6E9F2)),
    content = content)
