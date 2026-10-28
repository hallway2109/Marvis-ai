package com.marvis.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Segment
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Emotional, deep-toned text-to-speech with error recovery.
 *  - Each sentence is queued with its own pitch / rate (VoiceEmotionEngine), pitch multiplied by [depth] for a deep voice.
 *  - Picks a male-sounding installed voice automatically (Android has no official gender flag, so this uses the known
 *    Google voice codes) unless the user chose a specific voice in Settings.
 *  - If the engine is not ready yet, speech is kept and said as soon as it is. If the engine fails, it is rebuilt.
 */
class TtsManager(
    private val ctx: Context,
    private val depth: () -> Float = { 1f },
    private val preferredVoice: () -> String = { "" },
    private val onState: (Boolean, Emotion?) -> Unit,
) {
    companion object {
        /** Parts of Google voice names that are male voices (en-US iol/iom/tpd, en-GB rjs/gbd/gbb, en-IN end/ene, hi-IN hid/hic). */
        private val MALE_HINTS = listOf("-iol-", "-iom-", "-tpd-", "-rjs-", "-gbd-", "-gbb-", "-end-", "-ene-", "-hid-", "-hic-", "-male")
        private const val MAX_BACKLOG = 4
    }

    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initTries = 0
    private var counter = 0L
    private val emotionOf = ConcurrentHashMap<String, Emotion>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val backlog = ArrayList<Triple<List<Segment>, Boolean, Locale?>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            val e = id?.let { emotionOf[it] } ?: return
            onState(true, e)
        }
        override fun onDone(id: String?) = finished(id)
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = finished(id)
        override fun onError(id: String?, errorCode: Int) = finished(id)
        override fun onStop(id: String?, interrupted: Boolean) = finished(id)
    }

    init { start() }

    private fun start() {
        ready = false
        tts = TextToSpeech(ctx) { status -> onInit(status) }.also { it.setOnUtteranceProgressListener(listener) }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            if (initTries++ < 2) {                         // engine not up yet: rebuild it after a short wait
                runCatching { engine.shutdown() }
                ui.postDelayed({ start() }, 1500)
            }
            return
        }
        ready = true
        initTries = 0
        applyLocale(Locale.getDefault())
        val queued = ArrayList(backlog)
        backlog.clear()
        queued.forEach { (segs, flush, loc) -> speak(segs, flush, loc) }
    }

    fun speak(segments: List<Segment>, flush: Boolean = true, locale: Locale? = null) {
        if (segments.isEmpty()) return
        val engine = tts
        if (!ready || engine == null) {                    // not ready: remember it and say it when the engine is up
            backlog += Triple(segments, flush, locale)
            while (backlog.size > MAX_BACKLOG) backlog.removeAt(0)
            return
        }
        if (flush) stopQueue()
        applyLocale(locale ?: Locale.getDefault())

        val d = depth().coerceIn(0.6f, 1.0f)
        for (u in VoiceEmotionEngine.plan(segments)) {
            val text = clean(u.text)
            if (text.isBlank()) continue
            val id = "m${counter++}"
            emotionOf[id] = u.emotion
            pending += id
            engine.setPitch((u.pitch * d).coerceIn(0.5f, 1.6f))                    // deeper voice
            engine.setSpeechRate((u.rate * (0.96f + 0.04f * d)).coerceIn(0.5f, 1.8f))
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (result == TextToSpeech.ERROR) { recover(segments); return }
            if (u.pauseAfterMs > 0) {
                val pid = "p${counter++}"
                pending += pid
                engine.playSilentUtterance(u.pauseAfterMs, TextToSpeech.QUEUE_ADD, pid)
            }
        }
    }

    /** The engine returned an error: rebuild it once and say the same thing again. */
    private fun recover(segments: List<Segment>) {
        stopQueue()
        backlog += Triple(segments, true, null)
        runCatching { tts?.shutdown() }
        tts = null
        initTries = 0
        ui.postDelayed({ start() }, 400)
    }

    private fun applyLocale(l: Locale) {
        val engine = tts ?: return
        val r = engine.setLanguage(l)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (engine.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.US)
        }
        pickVoice(engine)
    }

    private fun pickVoice(engine: TextToSpeech) {
        val lang = (engine.voice?.locale ?: Locale.getDefault()).language
        val voices = runCatching { engine.voices }.getOrNull()
            ?.filter { it.locale.language == lang && !it.features.contains("notInstalled") } ?: return
        val wanted = preferredVoice()
        val chosen = if (wanted.isNotBlank()) voices.firstOrNull { it.name == wanted }
        else voices.firstOrNull { v ->
            !v.name.contains("female", true) && MALE_HINTS.any { h -> v.name.contains(h, true) }
        }
        if (chosen != null && engine.voice?.name != chosen.name) runCatching { engine.voice = chosen }
    }

    /** Installed voice names for the current language, for the voice picker in Settings. */
    fun voiceNames(): List<String> {
        val engine = tts?.takeIf { ready } ?: return emptyList()
        val lang = (engine.voice?.locale ?: Locale.getDefault()).language
        return runCatching { engine.voices }.getOrNull()
            ?.filter { it.locale.language == lang && !it.features.contains("notInstalled") }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    fun stop() {
        stopQueue()
        onState(false, null)
    }

    private fun stopQueue() {
        pending.clear()
        emotionOf.clear()
        runCatching { tts?.stop() }
    }

    private fun finished(id: String?) {
        if (id == null || !pending.remove(id)) return
        emotionOf.remove(id)
        if (pending.isEmpty()) onState(false, null)
    }

    fun release() {
        ui.removeCallbacksAndMessages(null)
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun clean(s: String) = s
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[*#_`>~|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
