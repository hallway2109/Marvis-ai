package com.marvis.ai.device

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Play / pause / rewind / forward / next / previous for whatever is playing (YouTube, Spotify, VLC...).
 * 1) With notification access: talks to the app's MediaSession directly (exact seek, knows what is playing).
 * 2) Without it: sends the same media key presses a headset button would.
 */
class MediaControl(private val ctx: Context) {
    private val sessionManager = ctx.getSystemService(MediaSessionManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private fun sessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(ComponentName(ctx, MarvisNotificationListener::class.java))
    } catch (e: SecurityException) {
        emptyList()                              // notification access not granted: use media keys
    }

    private fun playing(list: List<MediaController>) =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    fun play(): Boolean {
        val list = sessions()
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED } ?: list.firstOrNull()
        if (c != null) { c.transportControls.play(); return true }
        return key(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(): Boolean {
        val c = playing(sessions())
        if (c != null) { c.transportControls.pause(); return true }
        return key(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun toggle(): Boolean = if (playing(sessions()) != null) pause() else play()

    fun next(): Boolean {
        val c = playing(sessions()) ?: sessions().firstOrNull()
        if (c != null) { c.transportControls.skipToNext(); return true }
        return key(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(): Boolean {
        val c = playing(sessions()) ?: sessions().firstOrNull()
        if (c != null) { c.transportControls.skipToPrevious(); return true }
        return key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** Positive = forward, negative = rewind. */
    fun seekBy(deltaMs: Long): Boolean {
        val c = playing(sessions()) ?: sessions().firstOrNull()
        if (c != null) {
            val st = c.playbackState
            if (st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L) {
                c.transportControls.seekTo((position(st) + deltaMs).coerceAtLeast(0L))
            } else if (deltaMs < 0) c.transportControls.rewind() else c.transportControls.fastForward()
            return true
        }
        return key(if (deltaMs < 0) KeyEvent.KEYCODE_MEDIA_REWIND else KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }

    private fun position(st: PlaybackState): Long {
        if (st.state != PlaybackState.STATE_PLAYING) return st.position
        val elapsed = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return st.position + (elapsed * st.playbackSpeed).toLong()
    }

    private fun key(code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return true
    }
}
