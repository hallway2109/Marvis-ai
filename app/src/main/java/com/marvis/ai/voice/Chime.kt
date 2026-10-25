package com.marvis.ai.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short audio cues: mic on, mic off / stop, "ready to listen", "heard you". */
object Chime {
    fun on() = play(ToneGenerator.TONE_PROP_ACK, 130)
    fun off() = play(ToneGenerator.TONE_PROP_NACK, 170)
    fun ready() = play(ToneGenerator.TONE_PROP_PROMPT, 110)
    fun heard() = play(ToneGenerator.TONE_PROP_BEEP, 90)

    private fun play(tone: Int, ms: Int) {
        runCatching {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            g.startTone(tone, ms)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { g.release() } }, ms + 250L)
        }
    }
}
