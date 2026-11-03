package com.marvis.ai.device

import kotlinx.coroutines.delay

/**
 * Device tools act on whatever window is active. If Marvis itself is on top, it must step aside first,
 * otherwise "scroll down" would scroll Marvis. MainActivity wires [minimize] and [visible].
 */
object AppForeground {
    @Volatile var visible = false
    @Volatile var minimize: (() -> Unit)? = null

    suspend fun ensureBackground() {
        if (visible) {
            minimize?.invoke()
            delay(700)
        }
    }
}
