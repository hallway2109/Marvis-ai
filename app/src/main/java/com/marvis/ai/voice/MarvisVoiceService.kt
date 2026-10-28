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
import android.os.PowerManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-on microphone service (foreground, type = microphone).
 *
 * Wake word: the name alone is enough ("Marvis", "Jarvis", "Friday" or your custom name). A greeting
 * ("Hey", "Hi", "Hello", "OK"...) in front is optional, unless "Require Hey" is switched on in Settings.
 *
 * Secret code: after "Unlock my device" (or any task that needs the code) the NEXT thing you say is taken as the
 * code, whole and unedited. No wake word, no "Hey". A wrong code gets "Denied request" and the task stops.
 * While the code is awaited, a CPU and a screen wake lock keep the phone awake so it can't sleep mid-speech.
 *
 * Modes: WAKE, COMMAND, PAUSED (Marvis speaking / mic muted). While only thinking, only "stop" is accepted.
 * onTaskRemoved: swiping the app from Recents shuts everything down.
 */
class MarvisVoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.marvis.ai.STOP_VOICE"
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 7
        private const val COMMAND_WINDOW_MS = 15_000L
        private const val WATCHDOG_MS = 14_000L
        private const val SPEECH_WATCHDOG_MS = 30_000L
        private const val END_GRACE_MS = 3_500L
        private const val MAX_WAKE_POS = 20
        private const val MIN_CONFIDENCE = 0.20f
        private const val SECRET_LOCK_MS = 40_000L
        private const val COMMAND_LOCK_MS = 15_000L
        private const val REFRESH_MS = 10 * 60_000L          // rebuild the recognizer every 10 minutes while idle

        /** Optional greetings in front of the name. Edit to add more. */
        private const val GREETINGS = "hey|hi|hello|ok|okay|yo|hola|हे|हेय|ओके|अरे|ねえ|ヘイ"
        private val GREETING_START = Regex("^(?:$GREETINGS)", RegexOption.IGNORE_CASE)

        /** Spellings of each name the recognizer may return. Add entries here for a custom name. */
        private val MISHEARD = mapOf(
            "marvis" to listOf("marvin", "mar vis", "marvus", "mervis", "मार्विस", "मारविस", "マーヴィス", "マービス"),
            "jarvis" to listOf("jarvas", "jarvus", "jervis", "जार्विस", "ジャービス"),
            "friday" to listOf("fri day", "fry day", "fridays", "फ्राइडे", "フライデー"),
        )

        /**
         * @param needGreeting false = the greeting is optional ("Marvis", "Hey Marvis", "OK Marvis" all match).
         *                     true  = a greeting is required.
         */
        fun wakeRegex(name: String, needGreeting: Boolean = false): Regex {
            val n = name.trim().lowercase()
            val alts = (listOf(n) + MISHEARD[n].orEmpty()).distinct().joinToString("|") { Regex.escape(it) }
            val core = "(?:$alts)(?![\\p{L}\\p{N}])[ ,.!?、。।]*"
            val start = "(?<![\\p{L}\\p{N}])"
            val pattern = if (needGreeting) "$start(?:$GREETINGS)[ ,、]+$core"
                          else "$start(?:(?:$GREETINGS)[ ,、]*)?$core"
            return Regex(pattern, RegexOption.IGNORE_CASE)
        }

        fun start(ctx: Context) {
            if (!MarvisRuntime.active) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MarvisVoiceService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MarvisVoiceService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as MarvisApp
    private val brain get() = app.brain
    private val settings get() = app.settings

    private var recognizer: SpeechRecognizer? = null
    private var useCloud = false          // true = the cloud recognizer is in use right now
    private var forceCloud = false        // on-device language pack missing: always use the cloud one
    private var wake = wakeRegex(SettingsStore.DEFAULT_WAKE)

    private var speaking = false
    private var thinking = false
    private var paused = false
    private var stopOnly = false
    private var awaitingCommand = false
    private var commandDeadline = 0L

    private var listenLock: PowerManager.WakeLock? = null
    private var secretCpuLock: PowerManager.WakeLock? = null
    private var secretScreenLock: PowerManager.WakeLock? = null
    private var cmdCpuLock: PowerManager.WakeLock? = null
    private var cmdScreenLock: PowerManager.WakeLock? = null
    private var errorStreak = 0

    private val restartRunnable = Runnable { startListening() }

    /** The name was heard alone and the speaker paused: wake now instead of waiting for the final result. */
    private val partialWakeRunnable = Runnable { wakeFromPartial() }

    /** Keeps listening healthy: an idle recognizer that has silently gone deaf is replaced. */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!paused && !isCommandMode() && !brain.awaitingAnswer.value) {
                createRecognizer()
                restart(200)
            }
            main.postDelayed(this, REFRESH_MS)
        }
    }
    private val watchdogRunnable = Runnable {
        recognizer?.cancel()
        restart(300)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!MarvisRuntime.active) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Marvis voice", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }

        // Lock-screen listening: a CPU wake lock keeps the wake word working with the screen off.
        if (settings.lockScreenListening.value) {
            listenLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marvis:listen")
                .apply { setReferenceCounted(false); acquire() }
        }

        VoiceBus.micOn.value = true
        createRecognizer()
        VoiceBus.state.value = VoiceState(VoiceMode.WAKE)

        scope.launch {
            combine(settings.wakeName, settings.wakeNeedsHey) { n, h -> wakeRegex(n, h) }.collect { wake = it }
        }

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

        // Marvis asked "once or several times?" or "say your secret code": take the answer with no wake word.
        scope.launch {
            brain.awaitingAnswer.collect { waiting ->
                if (waiting) {
                    awaitingCommand = true
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
                    publishMode()
                }
                updatePaused()
            }
        }

        // While the secret code is awaited the phone must not fall asleep mid-speech.
        scope.launch { brain.awaitingSecret.collect { holdSecretLocks(it) } }

        main.postDelayed(refreshRunnable, REFRESH_MS)
        restart(300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.setAlwaysOn(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && (!settings.alwaysOn.value || !MarvisRuntime.active)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Swiped away from Recent Apps: stop everything. */
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
        holdSecretLocks(false)
        releaseCommandLocks()
        runCatching { listenLock?.takeIf { it.isHeld }?.release() }
        listenLock = null
        VoiceBus.state.value = VoiceState(VoiceMode.OFF)
        super.onDestroy()
    }

    // ---------------- wake locks for the secret-code window ----------------

    @Suppress("DEPRECATION")
    private fun holdSecretLocks(on: Boolean) {
        if (on) {
            val pm = getSystemService(PowerManager::class.java)
            if (secretCpuLock == null) {
                secretCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marvis:secret-cpu")
                    .apply { setReferenceCounted(false) }
            }
            if (secretScreenLock == null) {
                secretScreenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "marvis:secret-screen"
                ).apply { setReferenceCounted(false) }
            }
            runCatching { secretCpuLock?.acquire(SECRET_LOCK_MS) }
            runCatching { secretScreenLock?.acquire(SECRET_LOCK_MS) }
        } else {
            runCatching { secretCpuLock?.takeIf { it.isHeld }?.release() }
            runCatching { secretScreenLock?.takeIf { it.isHeld }?.release() }
        }
    }

    /**
     * While a command or the secret code is being spoken the CPU and the screen stay on, so the phone can't sleep
     * mid-sentence. (The screen lock only keeps an already-lit screen on; the secret-code lock above also wakes it.)
     */
    @Suppress("DEPRECATION")
    private fun syncCommandLock() {
        if (!(isCommandMode() || brain.awaitingSecret.value)) { releaseCommandLocks(); return }
        val pm = getSystemService(PowerManager::class.java)
        if (cmdCpuLock == null) {
            cmdCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marvis:cmd-cpu").apply { setReferenceCounted(false) }
        }
        if (cmdScreenLock == null) {
            cmdScreenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "marvis:cmd-screen").apply { setReferenceCounted(false) }
        }
        runCatching { cmdCpuLock?.acquire(COMMAND_LOCK_MS) }
        runCatching { cmdScreenLock?.acquire(COMMAND_LOCK_MS) }
    }

    private fun releaseCommandLocks() {
        runCatching { cmdCpuLock?.takeIf { it.isHeld }?.release() }
        runCatching { cmdScreenLock?.takeIf { it.isHeld }?.release() }
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
        // Wake-word scanning stays on-device (private). While you speak a COMMAND the cloud recognizer is used instead:
        // it copes much better with distance, accents and long sentences. Switch off in Settings > Recognition.
        val wantCloud = forceCloud || (isCommandMode() && settings.cloudCommands.value)
        if (wantCloud != useCloud || recognizer == null) {
            useCloud = wantCloud
            createRecognizer()
        }
        val r = recognizer ?: return

        val pref = settings.speechLang.value
        val tag = if (pref == "auto") Locale.getDefault().toLanguageTag() else pref

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (isCommandMode()) 2400L else 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (isCommandMode()) 1900L else 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (isCommandMode()) 2000L else 0L)

        if (pref == "auto" && Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            intent.putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                arrayListOf("en-IN", "hi-IN", "ja-JP")
            )
        }

        armWatchdog(WATCHDOG_MS)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            restart(1000)
        }
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

    private fun disarmWatchdog() {
        main.removeCallbacks(watchdogRunnable)
    }

    /** Pause while Marvis speaks or the mic is muted. While only thinking, keep listening for "stop". */
    private fun updatePaused() {
        val shouldPause = speaking || !VoiceBus.micOn.value
        stopOnly = thinking && !speaking && VoiceBus.micOn.value && !brain.awaitingAnswer.value

        if (shouldPause == paused) {
            publishMode()
            return
        }
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

    private fun isCommandMode(): Boolean =
        awaitingCommand && System.currentTimeMillis() < commandDeadline

    private fun publishMode() {
        val mode = when {
            paused -> VoiceMode.PAUSED
            isCommandMode() -> VoiceMode.COMMAND
            else -> VoiceMode.WAKE
        }
        VoiceBus.state.value = VoiceBus.state.value.copy(mode = mode)
        syncCommandLock()
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /**
     * Unclear or silent audio while a command was expected: say it once and leave command mode.
     * While the secret code is awaited we stay silent and keep listening instead; the brain cancels the
     * request after 25 seconds without a code.
     */
    private fun onUnclear() {
        if (brain.awaitingSecret.value) return
        if (!isCommandMode()) return
        awaitingCommand = false
        Chime.off()
        brain.sayPhrase(Phrase.CANT_HEAR, Emotion.CONCERNED)
        publishMode()
    }

    // ---------------- recognizer callbacks ----------------

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(p: Bundle?) {
            if (isCommandMode()) Chime.ready()
        }

        override fun onBeginningOfSpeech() {
            armWatchdog(SPEECH_WATCHDOG_MS)
        }

        override fun onEndOfSpeech() {
            if (isCommandMode()) Chime.heard()
            armWatchdog(END_GRACE_MS)
        }

        override fun onRmsChanged(rms: Float) {
            VoiceBus.state.value = VoiceBus.state.value.copy(level = ((rms + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(p: Bundle?) {
            val t = best(p)
            if (t.isBlank() || brain.awaitingSecret.value) return        // never show the spoken secret code
            // Live text only while a command is being spoken, not for background chatter.
            if (isCommandMode()) VoiceBus.state.value = VoiceBus.state.value.copy(partial = t)

            // Fast wake: the name was said alone. If nothing follows for 0.8 s, wake now.
            main.removeCallbacks(partialWakeRunnable)
            if (!isCommandMode() && !paused && !stopOnly && !brain.awaitingAnswer.value) {
                val m = wake.find(t)
                if (m != null &&
                    m.range.first <= (if (GREETING_START.containsMatchIn(m.value)) MAX_WAKE_POS else 2) &&
                    t.substring(m.range.last + 1).isBlank()
                ) main.postDelayed(partialWakeRunnable, 800)
            }
        }

        override fun onResults(r: Bundle?) {
            disarmWatchdog()
            main.removeCallbacks(partialWakeRunnable)
            val text = best(r)
            if (text.isNotBlank()) errorStreak = 0
            val conf = r?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            VoiceBus.state.value = VoiceBus.state.value.copy(partial = "", level = 0f)

            if (!paused) {
                val lowConfidence = conf in 0.01f..MIN_CONFIDENCE && isCommandMode() && !brain.awaitingSecret.value
                when {
                    text.isBlank() || lowConfidence -> onUnclear()
                    else -> handle(text)
                }
                if (!paused) restart(200)
            }
        }

        override fun onError(code: Int) {
            disarmWatchdog()
            main.removeCallbacks(partialWakeRunnable)
            VoiceBus.state.value = VoiceBus.state.value.copy(partial = "", level = 0f)
            if (paused) return
            // Real errors (not "heard nothing") four times in a row: the recognizer is broken, build a fresh one.
            if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && ++errorStreak >= 4) {
                errorStreak = 0
                createRecognizer()
            }

            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    onUnclear()
                    if (!paused) restart(150)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.cancel()
                    restart(900)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopSelf()
                12, 13 -> {
                    forceCloud = true
                    useCloud = true
                    createRecognizer()
                    restart(500)
                }
                else -> restart(1200)
            }
        }

        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ---------------- wake word and command routing ----------------

    private fun handle(text: String) {
        // 1. Secret code: the whole utterance is the code. No wake word, no "Hey", no stop-word or name stripping.
        //    The brain checks it; a wrong code gets "Denied request" and the task is aborted.
        if (brain.awaitingSecret.value) {
            awaitingCommand = false
            brain.ask(text)
            return
        }

        if (brainStop(text)) {
            awaitingCommand = false
            Chime.off()
            brain.cancelAll()
            return
        }
        if (stopOnly) return

        val inCommand = isCommandMode()
        awaitingCommand = false

        // The name alone is enough. Without a greeting it must be the first word, so "see you Friday" doesn't wake it.
        val m = wake.find(text)?.takeIf {
            it.range.first <= (if (GREETING_START.containsMatchIn(it.value)) MAX_WAKE_POS else 2)
        }

        val command = when {
            m != null -> text.substring(m.range.last + 1).trim()
            inCommand -> text.trim()                                              // follow-up or tap: whole utterance
            settings.unlockWithoutWake.value && brain.isUnlockPhrase(text) -> text.trim()   // "Unlock my device"
            else -> return                                                        // background chatter: ignore
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

    private fun wakeFromPartial() {
        if (paused || isCommandMode() || stopOnly) return
        recognizer?.cancel()
        awaitingCommand = true
        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS
        publishMode()
        Chime.on()
        brain.sayPhrase(Phrase.YES, Emotion.HAPPY)
        restart(900)                              // safety net; the normal resume after "Yes?" restarts it too
    }

    /** "Marvis, stop" or just "stop": the wake word is removed before checking. */
    private fun brainStop(text: String): Boolean =
        brain.isStopCommand(wake.replaceFirst(text, "").trim().ifBlank { text })

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MarvisVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Marvis is listening")
            .setContentText("Say \"${settings.wakeName.value}\" anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .build()
    }
}
