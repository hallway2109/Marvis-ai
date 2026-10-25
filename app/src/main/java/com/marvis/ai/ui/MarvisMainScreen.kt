package com.marvis.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvis.ai.ai.ApiStatus
import com.marvis.ai.ai.ChatMessage
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Role
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Voice-first home: orb, live captions, and three buttons (screen share, talk/stop, settings). No text input. */
@Composable
fun MarvisMainScreen(vm: MarvisViewModel, onOpenAccessibility: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.showSettings) {
        BackHandler { vm.closeSettings() }
        SettingsScreen(apiStatus = s.apiStatus, apiDetail = s.apiDetail,
            onTestKey = vm::testConnection, onClose = vm::closeSettings)
        return
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MarvisBgTop, MarvisBgBottom)))
            .systemBarsPadding()
    ) {
        TopBar(s, onApiClick = vm::openSettings, onA11y = onOpenAccessibility, onListenToggle = vm::toggleListening)

        // Tap the orb: talk now, or stop if Marvis is thinking / speaking.
        Box(
            Modifier.fillMaxWidth().height(250.dp).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { vm.onOrbTap() },
            contentAlignment = Alignment.Center
        ) { MarvisOrb(s.orb, s.emotion, s.level, Modifier.fillMaxSize()) }

        val caption = when {
            s.voice.partial.isNotBlank() -> s.voice.partial
            s.orb == OrbState.IDLE && s.voiceRunning && s.micOn -> "Say the wake word, or tap the orb"
            s.orb == OrbState.IDLE && s.voiceRunning && !s.micOn -> "Mic is off"
            s.orb == OrbState.IDLE -> "Tap the orb to talk"
            else -> ""
        }
        if (caption.isNotEmpty()) {
            Text(caption, Modifier.padding(horizontal = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                color = Color(0xFFB8C0D8), fontSize = 14.sp)
        }

        val list = rememberLazyListState()
        LaunchedEffect(s.messages.size) { if (s.messages.isNotEmpty()) list.animateScrollToItem(s.messages.lastIndex) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.messages, key = { it.id }) { Caption(it) }
        }

        ControlRow(s, onShare = vm::toggleScreenShare, onTalk = vm::onOrbTap, onSettings = vm::openSettings)
    }
}

@Composable
private fun TopBar(s: UiState, onApiClick: () -> Unit, onA11y: () -> Unit, onListenToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text("MARVIS", fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
            Text(when (s.orb) {
                OrbState.IDLE -> if (s.voiceRunning) "Listening for wake word" else "Ready"
                OrbState.LISTENING -> "Listening…"
                OrbState.THINKING -> "Thinking…"
                OrbState.SPEAKING -> "Speaking"
            }, fontSize = 12.sp, color = Color(0xFF8A93AD))
        }
        Column(horizontalAlignment = Alignment.End) {
            Row {
                IconButton(onClick = onListenToggle) {
                    Icon(if (s.voiceRunning) Icons.Rounded.Headset else Icons.Rounded.HeadsetOff, "Always listening",
                        tint = if (s.voiceRunning) Color(0xFF5CE1A0) else Color(0xFF8A93AD))
                }
                IconButton(onClick = onA11y) {
                    Icon(Icons.Rounded.Accessibility, "Accessibility",
                        tint = if (s.a11yEnabled) Color(0xFF5CE1A0) else Color(0xFFFFB74D))
                }
            }
            ApiChip(s.apiStatus, onApiClick)
        }
    }
}

@Composable
private fun ApiChip(status: ApiStatus, onClick: () -> Unit) {
    val (label, color) = when (status) {
        ApiStatus.CONNECTED -> "Connected" to Color(0xFF5CE1A0)
        ApiStatus.KEY_MISSING -> "Key missing" to Color(0xFFFFB74D)
        ApiStatus.CHECKING -> "Checking…" to Color(0xFF8A93AD)
        ApiStatus.ERROR -> "API error" to Color(0xFFFF5C7A)
    }
    Row(
        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x221E2640))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = color)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.Key, "API key", Modifier.size(14.dp), tint = Color(0xFF8A93AD))
    }
}

/** Read-only caption of what was said. */
@Composable
private fun Caption(m: ChatMessage) {
    when (m.role) {
        Role.SYSTEM -> Text(m.text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            fontSize = 12.sp, color = Color(0xFFFFB74D))
        else -> {
            val user = m.role == Role.USER
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                if (!user && m.emotion != null) {
                    Text(m.emotion.tag, fontSize = 10.sp, color = Color(m.emotion.argb),
                        modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
                Text(m.text,
                    Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (user) Color(0xFF2A3350) else Color(0x331E2640))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 15.sp, color = Color(0xFFE6E9F2))
            }
        }
    }
}

@Composable
private fun ControlRow(s: UiState, onShare: () -> Unit, onTalk: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton(48.dp, Color(0xFF1F2638), onShare) {
            Icon(if (s.screenShare) Icons.Rounded.ScreenShare else Icons.Rounded.StopScreenShare, "Screen share",
                tint = if (s.screenShare) Color(0xFF5CE1A0) else Color(0xFF8A93AD))
        }
        RoundButton(72.dp, if (s.orb == OrbState.LISTENING) Color(0xFFFF5C7A) else Color(0xFF3B4A7A), onTalk) {
            val busy = s.thinking || s.speaking || s.orb == OrbState.LISTENING
            Icon(if (busy) Icons.Rounded.Stop else Icons.Rounded.Mic, if (busy) "Stop" else "Talk", Modifier.size(32.dp), tint = Color.White)
        }
        RoundButton(48.dp, Color(0xFF1F2638), onSettings) {
            Icon(Icons.Rounded.Settings, "Settings", tint = Color(0xFF8A93AD))
        }
    }
}

@Composable
private fun RoundButton(size: androidx.compose.ui.unit.Dp, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        content()
    }
}

/** Glowing orb: colour = emotion, energy = state / mic level, rippling wave ring. */
@Composable
fun MarvisOrb(state: OrbState, emotion: Emotion, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "phase")
    val breathe by t.animateFloat(0.96f, 1.04f,
        infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val amp by animateFloatAsState(level, tween(120), label = "amp")
    val target = if (state == OrbState.THINKING) Color(0xFF8E7CFF) else Color(emotion.argb)
    val color by animateColorAsState(target, tween(600), label = "color")

    val energy = when (state) {
        OrbState.IDLE -> 0.12f + amp * 0.3f
        OrbState.LISTENING -> 0.25f + amp * 0.75f
        OrbState.THINKING -> 0.45f
        OrbState.SPEAKING -> 0.40f + 0.30f * (sin(phase * 5f) * 0.5f + 0.5f)
    }
    val spin = if (state == OrbState.THINKING) phase * 3f else phase

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 4.2f * breathe * (1f + energy * 0.12f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), c, r * 2.6f), r * 2.6f, c)
        for (i in 0..2) {
            val rr = r * (1.35f + i * 0.28f) + r * 0.12f * energy * sin(phase * (i + 1) + i)
            drawCircle(color.copy(alpha = 0.22f - i * 0.06f), rr, c, style = Stroke(1.5f.dp.toPx()))
        }
        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val a = i / steps.toFloat() * 2f * PI.toFloat()
            val rad = r * 1.18f + r * 0.16f * energy * sin(6f * a + spin) + r * 0.06f * energy * sin(11f * a - spin * 1.6f)
            val p = Offset(c.x + rad * cos(a), c.y + rad * sin(a))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color.copy(alpha = 0.85f), style = Stroke(2.5f.dp.toPx()))
        drawCircle(Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.25f)),
            c - Offset(r * 0.25f, r * 0.3f), r * 1.1f), r, c)
    }
}
