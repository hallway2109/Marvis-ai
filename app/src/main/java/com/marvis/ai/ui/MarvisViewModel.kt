package com.marvis.ai.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marvis.ai.MarvisApp
import com.marvis.ai.ai.ApiStatus
import com.marvis.ai.ai.ChatMessage
import com.marvis.ai.ai.Emotion
import com.marvis.ai.device.MarvisAccessibilityService
import com.marvis.ai.voice.Chime
import com.marvis.ai.voice.MarvisVoiceService
import com.marvis.ai.voice.VoiceBus
import com.marvis.ai.voice.VoiceMode
import com.marvis.ai.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    val emotion: Emotion = Emotion.CALM,
    val apiStatus: ApiStatus = ApiStatus.CHECKING,
    val apiDetail: String = "",
    val a11yEnabled: Boolean = false,
    val voice: VoiceState = VoiceState(),
    val micOn: Boolean = true,
    val showSettings: Boolean = false,
) {
    val voiceRunning get() = voice.mode != VoiceMode.OFF
    val orb: OrbState get() = when {
        thinking -> OrbState.THINKING
        speaking -> OrbState.SPEAKING
        voice.mode == VoiceMode.COMMAND -> OrbState.LISTENING
        else -> OrbState.IDLE
    }
    val level: Float get() = when (voice.mode) {
        VoiceMode.COMMAND -> voice.level
        VoiceMode.WAKE -> voice.level * 0.35f
        else -> 0f
    }
}

sealed interface UiEvent {
    data object RequestMic : UiEvent
    data object RequestProjection : UiEvent
}

private data class Local(val showSettings: Boolean = false, val a11y: Boolean = false)

class MarvisViewModel(private val app: Application) : AndroidViewModel(app) {
    private val marvis = app as MarvisApp
    private val brain = marvis.brain
    private val settings = marvis.settings

    private val local = MutableStateFlow(Local())
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val state: StateFlow<UiState> = combine(brain.state, VoiceBus.state, VoiceBus.micOn, local) { b, v, mic, l ->
        UiState(
            messages = b.messages, thinking = b.thinking, speaking = b.speaking, emotion = b.emotion,
            apiStatus = b.apiStatus, apiDetail = b.apiDetail,
            a11yEnabled = l.a11y, voice = v, micOn = mic, showSettings = l.showSettings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    /** Orb / mic button: stop if busy, otherwise talk now (starting the listening service if needed). */
    fun onOrbTap() {
        val s = state.value
        when {
            s.thinking || s.speaking -> { brain.cancelAll(); Chime.off() }
            !hasMic() -> _events.tryEmit(UiEvent.RequestMic)
            else -> talkNow()
        }
    }

    private fun talkNow() {
        viewModelScope.launch {
            Chime.on()
            VoiceBus.micOn.value = true
            if (state.value.voiceRunning) {
                VoiceBus.requests.emit(Unit)
            } else {
                settings.setAlwaysOn(true)
                MarvisVoiceService.start(app)
                delay(1000)
                VoiceBus.requests.emit(Unit)
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean, thenListen: Boolean) {
        if (!granted) return
        settings.setAlwaysOn(true)
        MarvisVoiceService.start(app)
        if (thenListen) viewModelScope.launch { delay(1000); VoiceBus.requests.emit(Unit) }
    }

    /** Top-bar headset: always-listening on/off. */
    fun toggleListening() {
        when {
            state.value.voiceRunning -> { settings.setAlwaysOn(false); MarvisVoiceService.stop(app) }
            !hasMic() -> _events.tryEmit(UiEvent.RequestMic)
            else -> { settings.setAlwaysOn(true); MarvisVoiceService.start(app) }
        }
    }

    fun restoreVoice() {
        if (settings.alwaysOn.value && hasMic() && !state.value.voiceRunning) MarvisVoiceService.start(app)
    }

    fun requestProjection() { _events.tryEmit(UiEvent.RequestProjection) }

    fun openSettings() = local.update { it.copy(showSettings = true) }
    fun closeSettings() = local.update { it.copy(showSettings = false) }
    fun testConnection() = brain.refreshStatus()
    fun refreshA11y() = local.update { it.copy(a11y = MarvisAccessibilityService.isRunning) }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
