package com.marvis.ai.device

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Smart-mute: while Marvis listens or talks and a video / music is playing, the media volume fades to zero instead
 * of pausing. When the conversation ends, the volume fades back and the player is rewound by exactly the time it
 * was muted, so no dialogue is missed.
 *
 * Limits of Android, stated plainly:
 *  - No app may mute ONE other app. The whole media volume is faded. To keep Marvis audible, its own voice is sent
 *    through the ALARM volume while the media is muted (set that volume in Android's sound settings).
 *  - The exact rewind needs a media session, which needs notification access for Marvis (Settings > Permissions).
 *    Without it the media is still muted and restored, just not rewound.
 *  - The original volume is saved to disk before muting and restored on the next start, so a crash can't leave you silent.
 */
class SmartAudio(private val ctx: Context, private val selfSpeaking: () -> Boolean) {
    companion object {
        private const val KEY = "smart_mute_restore"
        private const val MAX_MUTE_MS = 180_000L
        private const val FADE_STEPS = 6
        private const val FADE_STEP_MS = 40L
    }

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val media = MediaControl(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("marvis_prefs", Context.MODE_PRIVATE)

    var enabled: () -> Boolean = { true }
    /** Called with a one-line note for the captions (for example, "exact rewind needs notification access"). */
    var onNote: (String) -> Unit = {}

    private var engaged = false
    private var savedVolume = -1
    private var mutedAt = 0L
    private var rewindable = false
    private var noted = false

    private val releaseRunnable = Runnable { doRelease() }
    private val failsafe = Runnable { doRelease() }

    /** True while the media volume is faded out: Marvis's voice then uses the alarm channel. */
    val isMuted: Boolean get() = engaged && savedVolume >= 0

    fun engage() {
        main.removeCallbacks(releaseRunnable)
        if (engaged) {
            main.removeCallbacks(failsafe)
            main.postDelayed(failsafe, MAX_MUTE_MS)
            return
        }
        if (!enabled()) return
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) return        // Marvis's voice would be silent
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volume == 0) return
        val hasSession = media.hasPlayingSession()
        val playing = hasSession || (!selfSpeaking() && audio.isMusicActive)
        if (!playing) return

        savedVolume = volume
        prefs.edit().putInt(KEY, volume).apply()                                  // crash safety
        rewindable = hasSession
        mutedAt = SystemClock.elapsedRealtime()
        engaged = true
        fade(volume, 0, null)
        main.postDelayed(failsafe, MAX_MUTE_MS)
    }

    /** The conversation may be over: restore after [delayMs] unless something engages again first. */
    fun release(delayMs: Long = 1400L) {
        if (!engaged) return
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, delayMs)
    }

    /** Restore right now (app closing). */
    fun forceRelease() = doRelease()

    private fun doRelease() {
        main.removeCallbacks(releaseRunnable)
        main.removeCallbacks(failsafe)
        if (!engaged) return
        val mutedMs = SystemClock.elapsedRealtime() - mutedAt
        val restore = savedVolume
        engaged = false
        savedVolume = -1

        // Rewind first (while still silent), then fade the sound back in.
        if (mutedMs > 700) {
            if (rewindable) media.seekBy(-mutedMs)
            else if (!noted) {
                noted = true
                onNote("Media was muted but not rewound: allow notification access for Marvis in Settings > Permissions for exact rewind.")
            }
        }
        fade(0, restore) { prefs.edit().remove(KEY).apply() }
    }

    /** Call at app start: if a crash left the media volume at zero, put it back. */
    fun restoreIfNeeded() {
        val saved = prefs.getInt(KEY, -1)
        if (saved < 0) return
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        }
        prefs.edit().remove(KEY).apply()
    }

    private fun fade(from: Int, to: Int, done: (() -> Unit)?) {
        for (i in 1..FADE_STEPS) {
            main.postDelayed({
                val v = from + (to - from) * i / FADE_STEPS
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                if (i == FADE_STEPS) done?.invoke()
            }, FADE_STEP_MS * i)
        }
    }
}
