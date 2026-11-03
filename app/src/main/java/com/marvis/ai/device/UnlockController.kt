package com.marvis.ai.device

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.marvis.ai.security.LockType
import com.marvis.ai.security.SecurityStore
import kotlinx.coroutines.delay

/**
 * Wakes the screen, asks Android to show the code entry, waits until it is really on screen, then enters the saved
 * code through the accessibility service. Only called after the user's spoken command AND a correct secret voice code.
 *
 * Safety: the code is typed at most ONCE per unlock. If the entry screen never appears nothing is typed, so a failed
 * attempt can't count as wrong PIN guesses on the phone.
 */
class UnlockController(private val ctx: Context, private val security: SecurityStore) {

    suspend fun unlock(): Outcome {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return Outcome(true, "your phone is already unlocked")
        val s = MarvisAccessibilityService.instance
            ?: return Outcome(false, "the accessibility service is off")

        val type = security.lockType.value
        val cred = security.credential()
        if (type != LockType.SWIPE && cred == null) return Outcome(false, "no lock code is saved")

        wakeScreen()
        delay(600)
        UnlockActivity.launch(ctx)              // Android's own request: shows the code entry (or just unlocks)
        delay(1000)

        try {
            if (type == LockType.SWIPE) {
                if (waitUnlocked(km, 2500)) return Outcome(true, "unlocked")
                s.swipeUpFromBottom()
            } else {
                // Wait for the real entry screen. If it doesn't show, nudge it with a swipe and wait once more.
                if (!s.credentialUiReady(type, 3000)) {
                    s.swipeUpFromBottom()
                    delay(900)
                    if (!s.credentialUiReady(type, 4000)) {
                        return Outcome(false, "I couldn't find the ${label(type)} entry on the lock screen. ${s.describeLockScreen()}")
                    }
                }
                val result = when (type) {
                    LockType.PIN -> s.enterPin(cred!!)
                    LockType.PASSWORD -> s.enterPassword(cred!!)
                    LockType.PATTERN -> s.drawPattern(cred!!.split('-').mapNotNull { it.toIntOrNull() })
                    LockType.SWIPE -> Outcome(true, "")
                }
                if (!result.ok) return result
            }

            if (waitUnlocked(km, 4000)) return Outcome(true, "unlocked")
            return Outcome(false, "the phone is still locked, so please check the saved ${label(type)} in Settings")
        } finally {
            UnlockActivity.finishIfOpen()
        }
    }

    private suspend fun waitUnlocked(km: KeyguardManager, ms: Long): Boolean {
        var waited = 0L
        while (waited < ms) {
            delay(400)
            waited += 400
            if (!km.isKeyguardLocked) return true
        }
        return !km.isKeyguardLocked
    }

    private fun label(t: LockType) = when (t) {
        LockType.PIN -> "PIN"
        LockType.PASSWORD -> "password"
        LockType.PATTERN -> "pattern"
        LockType.SWIPE -> "screen"
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "marvis:unlock")
        wl.acquire(8_000L)
    }
}
