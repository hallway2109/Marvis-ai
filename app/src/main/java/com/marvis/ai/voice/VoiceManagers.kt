package com.marvis.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Segment
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Emotional text-to-speech. Each sentence is queued with its own pitch/rate, and pauses are queued as
 * silent utterances. onState(speaking, emotion) reports the current emotion while a sentence plays.
 */
class TtsManager(ctx: Context, private val onState: (Boolean, Emotion?) -> Unit) {
    private var ready = false
    private var counter = 0L
    private val emotionOf = ConcurrentHashMap<String, Emotion>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val tts = TextToSpeech(ctx) { status -> onInit(status) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                val e = id?.let { emotionOf[it] } ?: return
                onState(true, e)
            }
            override fun onDone(id: String?) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
            override fun onStop(id: String?, interrupted: Boolean) = finished(id)
        })
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
    }

    fun speak(segments: List<Segment>, flush: Boolean = true, locale: Locale? = null) {
        if (!ready || segments.isEmpty()) return
        if (flush) stopQueue()
        if (locale != null) applyLocale(locale)
        for (u in VoiceEmotionEngine.plan(segments)) {
            val text = clean(u.text)
            if (text.isBlank()) continue
            val id = "m${counter++}"
            emotionOf[id] = u.emotion
            pending += id
            tts.setPitch(u.pitch)
            tts.setSpeechRate(u.rate)
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (u.pauseAfterMs > 0) {
                val pid = "p${counter++}"
                pending += pid
                tts.playSilentUtterance(u.pauseAfterMs, TextToSpeech.QUEUE_ADD, pid)
            }
        }
    }

    private fun applyLocale(l: Locale) {
        val r = tts.setLanguage(l)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
        }
    }

    fun stop() {
        stopQueue()
        onState(false, null)
    }

    private fun stopQueue() {
        pending.clear()
        emotionOf.clear()
        tts.stop()
    }

    private fun finished(id: String?) {
        if (id == null || !pending.remove(id)) return
        emotionOf.remove(id)
        if (pending.isEmpty()) onState(false, null)
    }

    fun release() { tts.shutdown() }

    private fun clean(s: String) = s
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[*#_`>~|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
