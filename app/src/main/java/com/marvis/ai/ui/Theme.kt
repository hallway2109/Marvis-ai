package com.marvis.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MarvisBgTop = Color(0xFF03060B)
val MarvisBgBottom = Color(0xFF07111C)
val MarvisSurface = Color(0xFF0A1420)

@Composable
fun MarvisTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF2EE6D6), background = MarvisBgTop, surface = MarvisSurface,
        onSurface = Color(0xFFE6E9F2), onBackground = Color(0xFFE6E9F2)),
    content = content)
