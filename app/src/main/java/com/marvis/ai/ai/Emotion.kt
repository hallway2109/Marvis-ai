package com.marvis.ai.ai

/** Emotion -> TTS prosody + orb colour. */
enum class Emotion(val tag: String, val pitch: Float, val rate: Float, val argb: Long) {
    FRIENDLY("friendly", 1.08f, 1.02f, 0xFF4FC3F7),
    HAPPY("happy", 1.20f, 1.08f, 0xFFFFC857),
    EXCITED("excited", 1.35f, 1.22f, 0xFFFF5CCB),
    THOUGHTFUL("thoughtful", 0.80f, 0.88f, 0xFF8E7CFF),
    CONCERNED("concerned", 0.90f, 0.92f, 0xFF4FA3FF),
    CALM("calm", 0.80f, 0.85f, 0xFF5CE1C8),
    SAD("sad", 0.75f, 0.82f, 0xFF6B7FA8),
    PLAYFUL("playful", 1.25f, 1.12f, 0xFFFF8A5C),
    NEUTRAL("neutral", 1.00f, 1.00f, 0xFF7C9CFF);

    companion object {
        private val aliases = mapOf(
            "warm" to FRIENDLY, "laughing" to HAPPY, "laughs" to HAPPY, "proud" to HAPPY,
            "joy" to HAPPY, "curious" to THOUGHTFUL, "thinking" to THOUGHTFUL, "worried" to CONCERNED,
            "sorry" to CONCERNED, "serious" to CALM, "relaxed" to CALM, "witty" to PLAYFUL, "sarcastic" to PLAYFUL,
            "teasing" to PLAYFUL, "thrilled" to EXCITED, "sadly" to SAD)

        fun fromTag(t: String?): Emotion? {
            val k = t?.lowercase() ?: return null
            return entries.firstOrNull { it.tag == k } ?: aliases[k]
        }
    }
}
