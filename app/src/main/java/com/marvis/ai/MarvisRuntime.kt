package com.marvis.ai

import android.content.Context
import android.content.Intent
import com.marvis.ai.device.MarvisOverlayService
import com.marvis.ai.device.ScreenCaptureHelper
import com.marvis.ai.device.ScreenCaptureService
import com.marvis.ai.voice.MarvisVoiceService
import com.marvis.ai.voice.VoiceBus
import com.marvis.ai.voice.VoiceMode
import com.marvis.ai.voice.VoiceState

/**
 * Single kill switch. Called when the user swipes Marvis away from Recent Apps (onTaskRemoved) or taps Quit.
 * While [active] is false the ActionRouter refuses to touch the device and the brain refuses new work,
 * so nothing keeps searching or controlling the phone in the background. Opening the app again re-arms it.
 */
object MarvisRuntime {
    @Volatile var active = true
        private set

    fun resume() { active = true }

    fun shutdown(ctx: Context) {
        active = false
        val app = ctx.applicationContext as MarvisApp
        runCatching { app.brain.cancelAll() }          // cancels coroutines, in-flight HTTP calls and speech
        ScreenCaptureHelper.detach()
        runCatching { ctx.stopService(Intent(ctx, MarvisVoiceService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, MarvisOverlayService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) }
        VoiceBus.state.value = VoiceState(VoiceMode.OFF)
    }
}
