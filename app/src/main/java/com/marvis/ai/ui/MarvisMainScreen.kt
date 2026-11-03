package com.marvis.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvis.ai.ai.ApiStatus
import com.marvis.ai.ai.ChatMessage
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Role
import com.marvis.ai.hologram.Holo3D
import com.marvis.ai.hologram.Meshes
import com.marvis.ai.hologram.Projected
import kotlin.math.PI
import kotlin.math.sin

private val Cyan = Color(0xFF2EE6D6)
private val Mono = FontFamily.Monospace

/** Voice-first HUD: a rotating wireframe hologram, thin cyan lines, live captions. No text input, no clutter. */
@Composable
fun MarvisMainScreen(vm: MarvisViewModel, onOpenAccessibility: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.showSettings) {
        BackHandler { vm.closeSettings() }
        SettingsScreen(apiStatus = s.apiStatus, apiDetail = s.apiDetail,
            onTestKey = vm::testConnection, onClose = vm::closeSettings)
        return
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MarvisBgTop, MarvisBgBottom)))) {
        HudGrid(Modifier.fillMaxSize())
        CornerBrackets(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp))

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(s, onApiClick = vm::openSettings, onA11y = onOpenAccessibility, onListenToggle = vm::toggleListening)

            // Tap the hologram: talk now, or stop if Marvis is thinking / speaking.
            Box(
                Modifier.fillMaxWidth().height(290.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { vm.onOrbTap() },
                contentAlignment = Alignment.Center
            ) { HoloOrb(s.orb, s.emotion, s.level, Modifier.fillMaxSize()) }

            val hint = when {
                s.voice.partial.isNotBlank() -> s.voice.partial
                s.orb == OrbState.IDLE && s.voiceRunning && s.micOn -> "say the wake word, or tap the hologram"
                s.orb == OrbState.IDLE && s.voiceRunning && !s.micOn -> "microphone off"
                s.orb == OrbState.IDLE -> "tap the hologram to talk"
                else -> ""
            }
            if (hint.isNotEmpty()) {
                Text(hint, Modifier.padding(horizontal = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    fontFamily = Mono, fontSize = 12.sp, color = Cyan.copy(alpha = 0.7f))
            }

            val list = rememberLazyListState()
            LaunchedEffect(s.messages.size) { if (s.messages.isNotEmpty()) list.animateScrollToItem(s.messages.lastIndex) }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 22.dp, vertical = 10.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.messages, key = { it.id }) { Caption(it) }
            }

            ControlRow(s, onTalk = vm::onOrbTap, onSettings = vm::openSettings)
        }
    }
}

@Composable
private fun TopBar(s: UiState, onApiClick: () -> Unit, onA11y: () -> Unit, onListenToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text("MARVIS", fontFamily = Mono, fontSize = 15.sp, letterSpacing = 8.sp, color = Cyan)
            val status = when (s.orb) {
                OrbState.IDLE -> if (s.voiceRunning) "standing by" else "ready"
                OrbState.LISTENING -> "listening"
                OrbState.THINKING -> "thinking"
                OrbState.SPEAKING -> "speaking"
            }
            Text("● $status", fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = Cyan.copy(alpha = 0.55f))
        }
        Column(horizontalAlignment = Alignment.End) {
            Row {
                IconButton(onClick = onListenToggle) {
                    Icon(if (s.voiceRunning) Icons.Rounded.Headset else Icons.Rounded.HeadsetOff, "Always listening",
                        tint = if (s.voiceRunning) Cyan else Color(0xFF5A6678), modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onA11y) {
                    Icon(Icons.Rounded.Accessibility, "Accessibility",
                        tint = if (s.a11yEnabled) Cyan else Color(0xFFFFB74D), modifier = Modifier.size(22.dp))
                }
            }
            ApiChip(s.apiStatus, onApiClick)
        }
    }
}

@Composable
private fun ApiChip(status: ApiStatus, onClick: () -> Unit) {
    val (label, color) = when (status) {
        ApiStatus.CONNECTED -> "online" to Cyan
        ApiStatus.KEY_MISSING -> "key missing" to Color(0xFFFFB74D)
        ApiStatus.CHECKING -> "checking" to Color(0xFF8A93AD)
        ApiStatus.ERROR -> "api error" to Color(0xFFFF5C7A)
    }
    Row(
        Modifier.padding(end = 10.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = Mono, fontSize = 11.sp, color = color)
    }
}

/** A line of the conversation: no bubbles, just labelled mono text. */
@Composable
private fun Caption(m: ChatMessage) {
    when (m.role) {
        Role.SYSTEM -> Text(m.text, Modifier.fillMaxWidth(), fontFamily = Mono, fontSize = 11.sp, color = Color(0xFFFFB74D).copy(alpha = 0.85f))
        else -> {
            val user = m.role == Role.USER
            val tag = if (user) Cyan else Color(m.emotion?.argb ?: 0xFF7C9CFF)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = tag, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp)) {
                        append(if (user) "YOU  " else "MARVIS  ")
                    }
                    append(m.text)
                },
                Modifier.fillMaxWidth(), fontFamily = Mono, fontSize = 13.sp, color = Color(0xFFD8E6EA))
        }
    }
}

@Composable
private fun ControlRow(s: UiState, onTalk: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(46.dp))
        val busy = s.thinking || s.speaking || s.orb == OrbState.LISTENING
        OutlineButton(78.dp, if (s.orb == OrbState.LISTENING) Color(0xFFFF5C7A) else Cyan, onTalk) {
            Icon(if (busy) Icons.Rounded.Stop else Icons.Rounded.Mic, if (busy) "Stop" else "Talk",
                Modifier.size(30.dp), tint = if (s.orb == OrbState.LISTENING) Color(0xFFFF5C7A) else Cyan)
        }
        OutlineButton(46.dp, Color(0xFF5A6678), onSettings) {
            Icon(Icons.Rounded.Settings, "Settings", Modifier.size(20.dp), tint = Color(0xFF8A93AD))
        }
    }
}

@Composable
private fun OutlineButton(size: Dp, color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.06f))
            .border(1.2.dp, color.copy(alpha = 0.7f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun HudGrid(modifier: Modifier) {
    Canvas(modifier) {
        val step = 44.dp.toPx()
        val c = Cyan.copy(alpha = 0.045f)
        var x = 0f
        while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
}

@Composable
private fun CornerBrackets(modifier: Modifier) {
    Canvas(modifier) {
        val len = 22.dp.toPx()
        val w = 1.5f.dp.toPx()
        val c = Cyan.copy(alpha = 0.55f)
        val r = size.width
        val b = size.height
        drawLine(c, Offset(0f, 0f), Offset(len, 0f), w); drawLine(c, Offset(0f, 0f), Offset(0f, len), w)
        drawLine(c, Offset(r, 0f), Offset(r - len, 0f), w); drawLine(c, Offset(r, 0f), Offset(r, len), w)
        drawLine(c, Offset(0f, b), Offset(len, b), w); drawLine(c, Offset(0f, b), Offset(0f, b - len), w)
        drawLine(c, Offset(r, b), Offset(r - len, b), w); drawLine(c, Offset(r, b), Offset(r, b - len), w)
    }
}

/** The centrepiece: a slowly rotating wireframe reactor. Colour = state / emotion, energy = voice level. */
@Composable
fun HoloOrb(state: OrbState, emotion: Emotion, level: Float, modifier: Modifier = Modifier) {
    val mesh = remember { Meshes.reactor(40) }
    val proj = remember { Projected(mesh.vertexCount) }
    val t = rememberInfiniteTransition(label = "holo")
    val spin by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.97f, 1.03f,
        infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val amp by animateFloatAsState(level, tween(120), label = "amp")
    val target = when (state) {
        OrbState.THINKING -> Color(0xFF8E7CFF)
        OrbState.LISTENING -> Color(0xFFB8FFF5)
        OrbState.SPEAKING -> Color(emotion.argb)
        OrbState.IDLE -> Cyan
    }
    val color by animateColorAsState(target, tween(500), label = "color")
    val energy = when (state) {
        OrbState.IDLE -> 0.12f + amp * 0.3f
        OrbState.LISTENING -> 0.25f + amp * 0.75f
        OrbState.THINKING -> 0.5f
        OrbState.SPEAKING -> 0.4f + 0.3f * (sin(spin * 8f) * 0.5f + 0.5f)
    }

    Canvas(modifier) {
        val c = center
        val base = size.minDimension * 0.30f * breathe
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent), c, base * 2.3f), base * 2.3f, c)

        // projector rings under the model
        for (k in 1..2) {
            val rx = base * (1.15f + 0.4f * k)
            val ry = rx * 0.26f
            drawOval(color.copy(alpha = 0.16f / k), Offset(c.x - rx, c.y + base * 1.15f - ry), Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
        }

        val yaw = spin * (if (state == OrbState.THINKING) 3f else 1f)
        Holo3D.project(mesh, yaw, -0.45f, 0f, 1f + 0.12f * energy, c.x, c.y, base, proj)
        val width = 1.2f.dp.toPx()
        for (i in 0 until mesh.edgeCount) {
            val a = mesh.ea[i]
            val b = mesh.eb[i]
            val al = Holo3D.depthAlpha((proj.z[a] + proj.z[b]) / 2f)
            drawLine(color.copy(alpha = al * 0.92f), Offset(proj.x[a], proj.y[a]), Offset(proj.x[b], proj.y[b]), width, StrokeCap.Round)
        }
        drawCircle(color.copy(alpha = 0.95f), base * (0.07f + 0.05f * energy), c)
    }
}
