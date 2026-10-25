package com.marvis.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvis.ai.MarvisApp
import com.marvis.ai.ai.ApiStatus
import com.marvis.ai.ai.SettingsStore
import com.marvis.ai.device.MarvisOverlayService
import com.marvis.ai.memory.Memory
import com.marvis.ai.memory.MemoryManager
import com.marvis.ai.memory.SaveResult
import com.marvis.ai.voice.MarvisVoiceService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    apiStatus: ApiStatus,
    apiDetail: String,
    onTestKey: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MarvisApp
    val settings = app.settings
    val savedKey by settings.apiKey.collectAsStateWithLifecycle()
    val model by settings.model.collectAsStateWithLifecycle()
    val wake by settings.wakeName.collectAsStateWithLifecycle()
    val overlayOn by settings.overlayOn.collectAsStateWithLifecycle()
    val alwaysOn by settings.alwaysOn.collectAsStateWithLifecycle()
    val speechLang by settings.speechLang.collectAsStateWithLifecycle()
    val thinkCue by settings.thinkCue.collectAsStateWithLifecycle()
    val webSearch by settings.webSearch.collectAsStateWithLifecycle()
    val askRepeat by settings.askRepeat.collectAsStateWithLifecycle()

    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelInput by remember(model) { mutableStateOf(model) }
    var customWake by remember { mutableStateOf("") }

    val (statusLabel, statusColor) = when (apiStatus) {
        ApiStatus.CONNECTED -> "● Connected" to Color(0xFF5CE1A0)
        ApiStatus.KEY_MISSING -> "● Key missing" to Color(0xFFFFB74D)
        ApiStatus.CHECKING -> "● Checking…" to Color(0xFF8A93AD)
        ApiStatus.ERROR -> "● API error" to Color(0xFFFF5C7A)
    }

    Column(
        Modifier.fillMaxSize().background(MarvisBgTop).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Light)
        }

        Section("OpenRouter API") {
            Text(statusLabel, color = statusColor, fontWeight = FontWeight.Medium)
            if (apiDetail.isNotBlank()) Text(apiDetail, fontSize = 12.sp, color = Color(0xFFB8C0D8))
            Text(if (savedKey.isBlank()) "No key saved" else "Saved key: ••••${savedKey.takeLast(4)}",
                fontSize = 12.sp, color = Color(0xFF8A93AD))
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it }, singleLine = true,
                label = { Text("Paste API key (sk-or-…)") },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key")
                    }
                },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (keyInput.isNotBlank()) { settings.saveApiKey(keyInput); keyInput = ""; onTestKey() }
                }) { Text("Save key") }
                OutlinedButton(onClick = { settings.saveApiKey(""); onTestKey() }) { Text("Remove") }
            }
            OutlinedTextField(value = modelInput, onValueChange = { modelInput = it }, singleLine = true,
                label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { settings.saveModel(modelInput); onTestKey() }) { Text("Save model") }
                TextButton(onClick = onTestKey) { Text("Test") }
            }
            Text("Free: google/gemini-2.5-flash:free or openrouter/free. If a model fails, Marvis falls back to openrouter/free.",
                fontSize = 11.sp, color = Color(0xFF8A93AD))
            SwitchRow("Web search for news, prices, weather", webSearch) { settings.setWebSearch(it) }
            Text("Web search is billed by OpenRouter per result, so it needs account credits even with a free model. Without credits Marvis answers without it.",
                fontSize = 11.sp, color = Color(0xFF8A93AD))
            SwitchRow("Say \"Let me think…\" when working", thinkCue) { settings.setThinkCue(it) }
        }

        Section("Wake word") {
            Text("Say \"Hey $wake\" to talk to me.", color = Color(0xFFB8C0D8))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.WAKE_PRESETS.forEach { name ->
                    FilterChip(selected = wake.equals(name, ignoreCase = true),
                        onClick = { settings.setWakeName(name) }, label = { Text("Hey $name") })
                }
            }
            OutlinedTextField(value = customWake, onValueChange = { customWake = it }, singleLine = true,
                label = { Text("Custom name (one word works best)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { settings.setWakeName(customWake); customWake = "" }, enabled = customWake.isNotBlank()) {
                Text("Use custom name")
            }
        }

        Section("Speech language") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.SPEECH_LANGS.forEach { (label, tag) ->
                    FilterChip(selected = speechLang == tag, onClick = { settings.setSpeechLang(tag) }, label = { Text(label) })
                }
            }
            Text("Auto uses your phone's language and tries to detect Hindi and Japanese where the recognizer supports it. If it mishears you, pick the language you're speaking. Marvis always replies in the language you used.",
                fontSize = 11.sp, color = Color(0xFF8A93AD))
        }

        Section("Floating orb") {
            SwitchRow("Show orb over other apps", overlayOn) { setOverlay(ctx, settings, it) }
            Text("Tap: mic on/off (tap while Marvis is busy = stop). Hold: menu. Drag: move.",
                fontSize = 12.sp, color = Color(0xFF8A93AD))
        }

        Section("Listening") {
            SwitchRow("Ask before repeating a task", askRepeat) { settings.setAskRepeat(it) }
            Text("If you say the same task more than once (\"scroll down, scroll down, scroll down\"), Marvis asks whether to do it once or several times, then how many. Saying \"3 times\" skips the question.",
                fontSize = 11.sp, color = Color(0xFF8A93AD))
            SwitchRow("Always listening for wake word", alwaysOn) {
                settings.setAlwaysOn(it)
                if (it) MarvisVoiceService.start(ctx) else MarvisVoiceService.stop(ctx)
            }
        }

        MemorySection(app.memory)

        Section("Permissions") {
            OutlinedButton(onClick = { openPage(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}") }) {
                Text("Battery: don't optimise Marvis")
            }
            OutlinedButton(onClick = { openPage(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}") }) {
                Text("Allow display over other apps")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 50 MB persistent memory: usage meter, a text box to write a new memory, and an editable list. */
@Composable
private fun MemorySection(memory: MemoryManager) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var limit by remember { mutableIntStateOf(20) }
    val items by remember(limit) { memory.observeRecent(limit) }.collectAsStateWithLifecycle(emptyList())
    val used by memory.observeUsedBytes().collectAsStateWithLifecycle(0L)
    val count by memory.observeCount().collectAsStateWithLifecycle(0)
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Memory?>(null) }

    fun toast(r: SaveResult) {
        when (r) {
            SaveResult.FULL -> Toast.makeText(ctx, "Memory is full (50 MB)", Toast.LENGTH_SHORT).show()
            SaveResult.EMPTY -> Toast.makeText(ctx, "Nothing to save", Toast.LENGTH_SHORT).show()
            SaveResult.OK -> {}
        }
    }

    Section("Memory (50 MB)") {
        LinearProgressIndicator(
            progress = { (used.toFloat() / MemoryManager.CAPACITY_BYTES).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
        Text("${formatBytes(used)} of 50 MB used · $count notes", fontSize = 12.sp, color = Color(0xFF8A93AD))
        Text("Say \"Remember this\" or \"Ye memory me save karo\" and Marvis saves it. You can also write notes here.",
            fontSize = 12.sp, color = Color(0xFF8A93AD))
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            label = { Text("Write a memory") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val t = draft
            scope.launch { val r = memory.add(t); if (r == SaveResult.OK) draft = "" else toast(r) }
        }, enabled = draft.isNotBlank()) { Text("Save memory") }

        items.forEach { m ->
            Row(
                Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(m.text, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { editing = m }) { Icon(Icons.Rounded.Edit, "Edit", tint = Color(0xFF8A93AD)) }
                IconButton(onClick = { scope.launch { memory.delete(m.id) } }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color(0xFFFF8A80)) }
            }
        }
        if (items.size >= limit) TextButton(onClick = { limit += 20 }) { Text("Show more") }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = MarvisSurface,
            title = { Text("Edit memory") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { scope.launch { toast(memory.update(m.id, text)); editing = null } }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MarvisSurface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        content()
    }
}

private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / 1024.0 / 1024.0)
}

private fun openPage(ctx: Context, action: String, data: String? = null) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (data != null) i.data = Uri.parse(data)
    ctx.startActivity(i)
}

private fun setOverlay(ctx: Context, settings: SettingsStore, on: Boolean) {
    if (on && !Settings.canDrawOverlays(ctx)) {
        openPage(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}")
        Toast.makeText(ctx, "Allow display over other apps, then turn the orb on again", Toast.LENGTH_LONG).show()
        return
    }
    settings.setOverlayOn(on)
    if (on) MarvisOverlayService.start(ctx) else MarvisOverlayService.stop(ctx)
}
