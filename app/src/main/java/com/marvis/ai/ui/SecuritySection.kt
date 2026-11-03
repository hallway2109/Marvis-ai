package com.marvis.ai.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvis.ai.ai.SettingsStore
import com.marvis.ai.security.LockType
import com.marvis.ai.security.SecurityStore

/** Settings > Lock screen & Security: device lock type + code / pattern, and the AI secret voice code. */
@Composable
fun SecuritySection(security: SecurityStore, settings: SettingsStore) {
    val ctx = LocalContext.current
    val lockType by security.lockType.collectAsStateWithLifecycle()
    val voiceUnlock by security.voiceUnlockEnabled.collectAsStateWithLifecycle()
    val requireForSend by security.requireCodeForSend.collectAsStateWithLifecycle()
    val hasSecret by security.hasSecret.collectAsStateWithLifecycle()
    val hasCredential by security.hasCredential.collectAsStateWithLifecycle()
    val lockListen by settings.lockScreenListening.collectAsStateWithLifecycle()
    val unlockNoWake by settings.unlockWithoutWake.collectAsStateWithLifecycle()

    var credential by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(listOf<Int>()) }
    var patternReset by remember { mutableIntStateOf(0) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    Section("Lock screen & Security") {
        if (!security.available) {
            Text("Secure storage isn't available on this phone, so voice unlock and the secret code are disabled.",
                color = Color(0xFFFF8A80), fontSize = 13.sp)
            return@Section
        }
        Text("Voice unlock enters your saved lock code on the lock screen after you say \"Unlock my device\" and then your secret voice code. " +
            "The lock code is stored encrypted on this phone. Only turn this on if you accept that.",
            fontSize = 12.sp, color = Color(0xFFB8C0D8))

        // ---- AI secret voice code ----
        Text("AI secret voice code", fontSize = 14.sp)
        OutlinedTextField(
            value = secret, onValueChange = { secret = it }, singleLine = true,
            label = { Text(if (hasSecret) "New secret phrase (one is saved)" else "Secret phrase (at least ${SecurityStore.MIN_SECRET_CHARS} characters)") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (security.saveSecret(secret)) { secret = ""; toast("Secret code saved") }
                else toast("Too short. Use at least ${SecurityStore.MIN_SECRET_CHARS} letters or digits")
            }, enabled = secret.isNotBlank()) { Text("Save secret code") }
            if (hasSecret) OutlinedButton(onClick = { security.clearSecret(); toast("Secret code removed") }) { Text("Remove") }
        }
        Text(if (hasSecret) "Secret code: set ✓" else "Secret code: not set",
            fontSize = 12.sp, color = if (hasSecret) Color(0xFF5CE1A0) else Color(0xFFFFB74D))
        Text("Pick a phrase you can say clearly, not just a short number. It's spoken aloud, so don't say it where others can hear. Wrong code = \"Denied request\"; 3 misses lock it for 5 minutes.",
            fontSize = 11.sp, color = Color(0xFF8A93AD))

        SwitchRow("Ask for the secret code before sending messages or email", requireForSend) { security.setRequireCodeForSend(it) }

        HorizontalDivider(color = Color(0x33FFFFFF))

        // ---- device lock ----
        Text("Phone lock type", fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LockType.SWIPE to "Swipe / None", LockType.PIN to "PIN", LockType.PASSWORD to "Password", LockType.PATTERN to "Pattern")
                .forEach { (t, label) ->
                    FilterChip(selected = lockType == t, onClick = {
                        security.setLockType(t); credential = ""; pattern = emptyList(); patternReset++
                    }, label = { Text(label) })
                }
        }

        when (lockType) {
            LockType.SWIPE -> Text("Marvis will wake the screen and swipe it open.", fontSize = 12.sp, color = Color(0xFF8A93AD))
            LockType.PIN, LockType.PASSWORD -> {
                OutlinedTextField(
                    value = credential, onValueChange = { credential = it }, singleLine = true,
                    label = { Text(if (lockType == LockType.PIN) "Phone PIN" else "Phone password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (lockType == LockType.PIN) KeyboardType.NumberPassword else KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val ok = security.saveCredential(credential)
                        if (ok) { credential = ""; toast("Saved (encrypted)") } else toast("Enter a value first")
                    }, enabled = credential.isNotBlank()) { Text("Save") }
                    if (hasCredential) OutlinedButton(onClick = { security.clearCredential(); toast("Removed") }) { Text("Remove") }
                }
            }
            LockType.PATTERN -> {
                Text("Draw your unlock pattern (at least 4 dots):", fontSize = 12.sp, color = Color(0xFF8A93AD))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PatternGrid(resetKey = patternReset, onChange = { pattern = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (security.saveCredential(pattern.joinToString("-"))) toast("Pattern saved (encrypted)")
                    }, enabled = pattern.size >= 4) { Text("Save pattern") }
                    OutlinedButton(onClick = { pattern = emptyList(); patternReset++ }) { Text("Clear") }
                    if (hasCredential) OutlinedButton(onClick = { security.clearCredential(); toast("Removed") }) { Text("Remove saved") }
                }
            }
        }
        if (lockType != LockType.SWIPE) {
            Text(if (hasCredential) "Lock code: saved ✓" else "Lock code: not saved",
                fontSize = 12.sp, color = if (hasCredential) Color(0xFF5CE1A0) else Color(0xFFFFB74D))
        }

        HorizontalDivider(color = Color(0x33FFFFFF))

        SwitchRow("Allow voice unlock (\"Unlock my device\")", voiceUnlock) { on ->
            if (!on) security.setVoiceUnlock(false)
            else if (!hasSecret) toast("Set your secret voice code first")
            else if (!security.unlockReady()) toast("Save your lock code or pattern first")
            else security.setVoiceUnlock(true)
        }
        SwitchRow("\"Unlock my device\" works without the wake word", unlockNoWake) { settings.setUnlockWithoutWake(it) }
        Text("After that phrase Marvis asks for your secret code and takes your very next words as the code, with no \"Hey\" needed. A wrong code gets \"Denied request\".",
            fontSize = 11.sp, color = Color(0xFF8A93AD))
        SwitchRow("Keep listening when the screen is off", lockListen) { settings.setLockScreenListening(it) }
        OutlinedButton(onClick = {
            val app = ctx.applicationContext as com.marvis.ai.MarvisApp
            app.brain.testUnlock()
            toast("Locking in a moment, then unlocking")
        }, enabled = hasCredential || lockType == LockType.SWIPE) { Text("Test unlock now") }
        Text("Locks your phone, then runs the unlock. Needs the Accessibility service on. A wrong saved code counts as a failed attempt on the phone itself, so check the code before testing.",
            fontSize = 11.sp, color = Color(0xFF8A93AD))
        Text("Needs the microphone service to keep running (turn off battery optimisation for Marvis). Restart listening after changing this. Some phones still pause the microphone when locked, and nothing works before the first unlock after a restart.",
            fontSize = 11.sp, color = Color(0xFF8A93AD))
    }
}

/** 3x3 pattern pad. Reports the dots in order, numbered 1..9 (1 = top-left, 9 = bottom-right). */
@Composable
fun PatternGrid(resetKey: Int, onChange: (List<Int>) -> Unit) {
    var seq by remember { mutableStateOf(listOf<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(resetKey) { seq = emptyList(); finger = null }

    fun dotCenter(i: Int): Offset {
        val cell = size.width / 3f
        return Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
    }
    fun hit(p: Offset): Int? {
        val cell = size.width / 3f
        for (i in 0 until 9) {
            val c = dotCenter(i)
            if ((p - c).getDistance() < cell * 0.3f) return i + 1
        }
        return null
    }

    Box(
        Modifier.size(240.dp)
            .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        seq = emptyList(); finger = o
                        hit(o)?.let { seq = listOf(it) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        finger = change.position
                        hit(change.position)?.let { if (it !in seq) seq = seq + it }
                    },
                    onDragEnd = { finger = null; onChange(seq) },
                    onDragCancel = { finger = null },
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val teal = Color(0xFF2EE6D6)
            for (k in 0 until seq.size - 1) {
                drawLine(teal, dotCenter(seq[k] - 1), dotCenter(seq[k + 1] - 1), strokeWidth = 4.dp.toPx())
            }
            val f = finger
            if (f != null && seq.isNotEmpty()) drawLine(teal.copy(alpha = 0.6f), dotCenter(seq.last() - 1), f, strokeWidth = 3.dp.toPx())
            for (i in 0 until 9) {
                val c = dotCenter(i)
                val on = (i + 1) in seq
                drawCircle(if (on) teal else Color(0xFF8A93AD), if (on) 11.dp.toPx() else 7.dp.toPx(), c)
                if (on) drawCircle(teal.copy(alpha = 0.35f), 20.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
