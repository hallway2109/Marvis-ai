package com.marvis.ai.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class VoiceMode { OFF, WAKE, COMMAND, PAUSED }
data class VoiceState(val mode: VoiceMode = VoiceMode.OFF, val level: Float = 0f, val partial: String = "")

object VoiceBus {
    val state = MutableStateFlow(VoiceState())
    /** Push-to-talk: the next utterance is a command even without the wake word. */
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** User mute switch (overlay orb tap). false = mic paused. */
    val micOn = MutableStateFlow(true)
}
