package com.marvis.ai.device

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible activity shown over the lock screen. It asks Android to dismiss the keyguard, which makes the system
 * bring up the real PIN / pattern / password entry (far more reliable than swiping). With no secure lock it just
 * unlocks. The accessibility service then types the saved code into the entry that appears.
 */
class UnlockActivity : Activity() {

    companion object {
        @Volatile private var current: UnlockActivity? = null

        fun launch(ctx: Context) {
            val i = Intent(ctx, UnlockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            runCatching { (MarvisAccessibilityService.instance ?: ctx).startActivity(i) }
        }

        fun finishIfOpen() {
            current?.let { a -> a.runOnUiThread { if (!a.isFinishing) a.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 30_000L)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
