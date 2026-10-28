package com.marvis.ai.voice

import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Segment
import kotlin.math.sin

/** One sentence to speak, with its own pitch/rate and the silence that follows it. */
data class Utterance(
    val text: String,
    val emotion: Emotion,
    val pitch: Float,
    val rate: Float,
    val pauseAfterMs: Long,
)

/**
 * Turns emotion-tagged segments into TTS-ready utterances.
 * Each sentence gets emotion base prosody + a small deterministic intonation wobble (so it doesn't sound flat),
 * and a pause chosen from its punctuation and emotion (thoughtful = longer pause before the next sentence).
 */
object VoiceEmotionEngine {

    private data class Prosody(val pitch: Float, val rate: Float, val variance: Float, val leadPauseMs: Long)

    private fun prosody(e: Emotion) = when (e) {
        Emotion.FRIENDLY -> Prosody(1.08f, 1.02f, 0.05f, 0L)
        Emotion.HAPPY -> Prosody(1.20f, 1.06f, 0.06f, 0L)
        Emotion.EXCITED -> Prosody(1.30f, 1.18f, 0.10f, 0L)
        Emotion.PLAYFUL -> Prosody(1.22f, 1.10f, 0.12f, 80L)
        Emotion.THOUGHTFUL -> Prosody(0.85f, 0.85f, 0.03f, 350L)
        Emotion.CALM -> Prosody(0.80f, 0.85f, 0.02f, 150L)
        Emotion.CONCERNED -> Prosody(0.88f, 0.90f, 0.04f, 200L)
        Emotion.SAD -> Prosody(0.78f, 0.82f, 0.02f, 300L)
        Emotion.NEUTRAL -> Prosody(1.00f, 1.00f, 0.03f, 0L)
    }

    fun plan(segments: List<Segment>): List<Utterance> {
        val out = ArrayList<Utterance>()
        var n = 0
        for (seg in segments) {
            val p = prosody(seg.emotion)
            for ((i, sentence) in splitSentences(seg.text).withIndex()) {
                val wobble = sin(n * 1.7f)
                n++
                if (i == 0 && p.leadPauseMs > 0 && out.isNotEmpty()) {
                    val prev = out.last()
                    out[out.lastIndex] = prev.copy(pauseAfterMs = maxOf(prev.pauseAfterMs, p.leadPauseMs))
                }
                out += Utterance(
                    text = sentence,
                    emotion = seg.emotion,
                    pitch = (p.pitch + p.variance * wobble).coerceIn(0.5f, 2.0f),
                    rate = (p.rate + p.variance * 0.5f * wobble).coerceIn(0.5f, 1.8f),
                    pauseAfterMs = pauseFor(sentence),
                )
            }
        }
        return out
    }

    private fun splitSentences(text: String): List<String> =
        Regex("[^.!?…]+[.!?…]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

    private fun pauseFor(s: String): Long = when (s.lastOrNull()) {
        '?' -> 380L
        '!' -> 300L
        '.' -> 350L
        '…' -> 550L
        else -> 150L
    }
}
