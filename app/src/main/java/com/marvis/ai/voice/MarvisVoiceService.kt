package com.marvis.ai.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.marvis.ai.MainActivity
import com.marvis.ai.MarvisApp
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Phrase
import com.marvis.ai.ai.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-on microphone service (foreground, type=microphone).
 *
 * Listening modes
 *  - WAKE:    scanning for the user's wake word; everything else is ignored silently.
 *  - COMMAND: after the wake word, tap or "Yes?": the next utterance is the command.
 *  - STOP-ONLY: while Marvis is thinking (not talking) only "stop / ruko / ストップ" is accepted.
 *  - PAUSED:  while Marvis is speaking (so it can't hear itself) or the user muted the mic.
 *
 * No stuck listening: a watchdog resets the recognizer when no callback arrives, a short grace timer follows
 * onEndOfSpeech, and every callback ends in exactly one restart.
 *
 * Unclear audio in COMMAND mode: Marvis says "I can't hear it, can you repeat that?" once, then leaves
 * command mode cleanly (no retry loop). Say the wake word or tap the orb to try again.
 *
 * onTaskRemoved: swiping the app from Recents shuts everything down.
 */
class MarvisVoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.marvis.ai.STOP_VOICE"
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 7
        private const val COMMAND_WINDOW_MS = 9_000L
        private const val WATCHDOG_MS = 14_000L
        private const val SPEECH_WATCHDOG_MS = 30_000L
        private const val END_GRACE_MS = 3_500L
        private const val MAX_WAKE_POS = 20
        private const val MIN_CONFIDENCE = 0.30f

        /** Names get recognised in several spellings and scripts. Add entries for your own custom name here. */
        private val MISHEARD = mapOf(
            "marvis" to listOf("marvin", "mar vis", "marvus", "mervis", "मार्विस", "मारविस", "मार्विन", "マーヴィス", "マービス", "マーヴィン"),
            "jarvis" to listOf("jarvas", "jarvus", "jervis", "जार्विस", "जारविस", "ジャービス", "ジャーヴィス"),
            "friday" to listOf("fri day", "fry day", "fridays", "फ्राइडे", "フライデー"),
        )

        fun wakeRegex(name: String): Regex {
            val n = name.trim().lowercase()
            val alts = (listOf(n) + MISHEARD[n].orEmpty()).distinct().joinToString("|") { Regex.escape(it) }
            val greet = "hey|hi|hello|ok|okay|yo|हे|हेय|ओके|अरे|ねえ|ヘイ"
            return Regex("(?<![\\p{L}\\p{N}])(?:(?:$greet)[ ,、]*)?(?:$alts)(?![\\p{L}\\p{N}])[ ,.!?、。।]*", RegexOption.IGNORE_CASE)
        }

        fun start(ctx: Context) {
            if (!MarvisRuntime.active) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MarvisVoiceService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MarvisVoiceService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as MarvisApp
    private val brain get() = app.brain
    private val settings get() = app.settings

    private var recognizer: SpeechRecognizer? = null
    private var useCloud = false
    private var wake = wakeRegex(SettingsStore.DEFAULT_WAKE)

    private var speaking = false
    private var thinking = false
    private var paused = false
    private var stopOnly = false
    private var awaitingCommand = false
    private var commandDeadline = 0L

    private val restartRunnable = Runnable { startListening() }
    private val watchdogRunnable = Runnable {
        recognizer?.cancel()
        restart(300)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!MarvisRuntime.active) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Marvis voice", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 12+ refuses microphone foreground services started from the background (or without RECORD_AUDIO).
            stopSelf()
            return
        }

        VoiceBus.micOn.value = true
        createRecognizer()
        VoiceBus.state.value = VoiceState(VoiceMode.WAKE)

        scope.launch { settings.wakeName.collect { wake = wakeRegex(it) } }
        scope.launch {
            brain.state.map { it.thinking to it.speaking }.distinctUntilChanged().collect { (t, s) ->
                thinking = t
                speaking = s
                updatePaused()
            }
        }
        scope.launch { VoiceBus.micOn.collect { updatePaused() } }
        scope.launch {
            VoiceBus.requests.collect {
                awaitingCommand = true
                commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
                recognizer?.cancel()
                restart(150)
            }
        }
        // Marvis asked "once or several times?": accept the answer without the wake word.
        scope.launch {
            brain.awaitingAnswer.collect { waiting ->
                if (waiting) {
                    awaitingCommand = true
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
                    publishMode()
                }
            }
        }
        restart(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.setAlwaysOn(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky restart by the system after a kill: only continue if the user wants it and the app wasn't dismissed.
        if (intent == null && (!settings.alwaysOn.value || !MarvisRuntime.active)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Swiped away from Recent Apps: kill the mic, the brain's jobs, the overlay and every service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        MarvisRuntime.shutdown(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        scope.cancel()
        VoiceBus.state.value = VoiceState(VoiceMode.OFF)
        super.onDestroy()
    }

    // ---------------- recognizer lifecycle ----------------

    private fun createRecognizer(): SpeechRecognizer? {
        recognizer?.destroy()
        val r = when {
            !useCloud && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            SpeechRecognizer.isRecognitionAvailable(this) -> SpeechRecognizer.createSpeechRecognizer(this)
            else -> null
        }
        r?.setRecognitionListener(listener)
        recognizer = r
        if (r == null) stopSelf()
        return r
    }

    private fun startListening() {
        if (paused || !MarvisRuntime.active) return
        val r = recognizer ?: createRecognizer() ?: return
        val pref = settings.speechLang.value
        val tag = if (pref == "auto") Locale.getDefault().toLanguageTag() else pref
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        if (pref == "auto" && Build.VERSION.SDK_INT >= 33) {
            // Honoured by recognizers that support it (typically the on-device one); ignored otherwise.
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                arrayListOf("en-IN", "hi-IN", "ja-JP"))
        }
        armWatchdog(WATCHDOG_MS)
        try { r.startListening(i) } catch (e: Exception) { restart(1000) }
        publishMode()
    }

    private fun restart(ms: Long) {
        main.removeCallbacks(restartRunnable)
        main.postDelayed(restartRunnable, ms)
    }

    private fun armWatchdog(ms: Long) {
        main.removeCallbacks(watchdogRunnable)
        main.postDelayed(watchdogRunnable, ms)
    }

    private fun disarmWatchdog() = main.removeCallbacks(watchdogRunnable)

    /** Pause while Marvis speaks or the user muted; while merely thinking keep a stop-only ear open. */
    private fun updatePaused() {
        val shouldPause = speaking || !VoiceBus.micOn.value
        stopOnly = thinking && !speaking && VoiceBus.micOn.value
        if (shouldPause == paused) { publishMode(); return }
        paused = shouldPause
        if (paused) {
            main.removeCallbacks(restartRunnable)
            disarmWatchdog()
            recognizer?.cancel()
        } else {
            if (awaitingCommand) commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
            restart(450)
        }
        publishMode()
    }

    private fun isCommandMode() = awaitingCommand && System.currentTimeMillis() < commandDeadline

    private fun publishMode() {
        val mode = when {
            paused -> VoiceMode.PAUSED
            isCommandMode() -> VoiceMode.COMMAND
            else -> VoiceMode.WAKE
        }
        VoiceBus.state.value = VoiceBus.state.value.copy(mode = mode)
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** Unclear or silent audio while a command was expected: say it once and leave command mode cleanly. */
    private fun onUnclear() {
        if (!isCommandMode()) return
        awaitingCommand = false
        Chime.off()
        brain.sayPhrase(Phrase.CANT_HEAR, Emotion.CONCERNED)
        publishMode()
    }

    // ---------------- recognizer callbacks ----------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { if (isCommandMode()) Chime.ready() }

        override fun onBeginningOfSpeech() { armWatchdog(SPEECH_WATCHDOG_MS) }

        override fun onEndOfSpeech() {
            if (isCommandMode()) Chime.heard()
            armWatchdog(END_GRACE_MS)            // results (or an error) must arrive soon, otherwise reset
        }

        override fun onRmsChanged(rms: Float) {
            VoiceBus.state.value = VoiceBus.state.value.copy(level = ((rms + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(p: Bundle?) {
            val t = best(p)
            if (t.isNotBlank()) VoiceBus.state.value = VoiceBus.state.value.copy(partial = t)
        }

        override fun onResults(r: Bundle?) {
            disarmWatchdog()
            val text = best(r)
            val conf = r?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            VoiceBus.state.value = VoiceBus.state.value.copy(partial = "", level = 0f)
            if (!paused) {
                when {
                    text.isBlank() || (conf in 0.01f..MIN_CONFIDENCE && isCommandMode()) -> onUnclear()
                    else -> handle(text)
                }
                if (!paused) restart(200)
            }
        }

        override fun onError(code: Int) {
            disarmWatchdog()
            VoiceBus.state.value = VoiceBus.state.value.copy(partial = "", level = 0f)
            if (paused) return
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    onUnclear()                    // only speaks when a command was expected
                    if (!paused) restart(150)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recognizer?.cancel(); restart(900) }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                12, 13 -> { useCloud = true; createRecognizer(); restart(500) }   // on-device language pack missing
                else -> restart(1200)
            }
        }

        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ---------------- wake word and command routing ----------------

    private fun handle(text: String) {
        val engineStop = brainStop(text)
        if (stopOnly) {                              // thinking: accept nothing but "stop"
            if (engineStop) { Chime.off(); brain.cancelAll() }
            return
        }
        if (engineStop) { awaitingCommand = false; Chime.off(); brain.cancelAll(); return }

        val inCommand = isCommandMode()
        awaitingCommand = false

        val m = wake.find(text)?.takeIf { it.range.first <= MAX_WAKE_POS }
        val command = when {
            m != null -> text.substring(m.range.last + 1).trim()
            inCommand -> text.trim()                 // push-to-talk / follow-up: whole utterance is the command
            else -> return                           // TV, chatter: ignored
        }

        if (command.isBlank()) {
            awaitingCommand = true
            commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
            publishMode()
            Chime.on()
            brain.sayPhrase(Phrase.YES, Emotion.HAPPY)
        } else {
            brain.ask(command)
        }
    }

    /** "Marvis, stop" or just "stop": the wake word is stripped before checking. */
    private fun brainStop(text: String): Boolean =
        brain.isStopCommand(wake.replaceFirst(text, "").trim().ifBlank { text })

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MarvisVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Marvis is listening")
            .setContentText("Say \"Hey ${settings.wakeName.value}\" anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
    }
}
