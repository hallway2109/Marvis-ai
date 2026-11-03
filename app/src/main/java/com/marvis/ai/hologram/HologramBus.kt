package com.marvis.ai.hologram

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class HoloSubject(val key: String, val title: String, val note: String) {
    REACTOR("REACTOR", "Arc reactor blueprint", "Stylised model. Fiction from the films, not real engineering."),
    ARMOR("ARMOR", "Mark 85 armour analysis", "Stylised model, not the official design. Fiction from the films."),
    GLOBE("GLOBE", "Earth hologram", "Wireframe globe."),
    HELIX("HELIX", "DNA helix", "Stylised double helix."),
}

sealed interface HoloEvent {
    data class Show(val subject: HoloSubject) : HoloEvent
    data class Details(val bullets: List<String>) : HoloEvent
    data object Close : HoloEvent
}

/** Brain -> floating HUD. The HUD lives in the overlay service, so holograms can appear over any app. */
object HologramBus {
    val events = MutableSharedFlow<HoloEvent>(extraBufferCapacity = 8)
    /** True while the overlay service is running and able to show a hologram. */
    val available = MutableStateFlow(false)
}
