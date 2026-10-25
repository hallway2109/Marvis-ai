package com.marvis.ai.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class Outcome(val ok: Boolean, val message: String)

/**
 * The "hands" of Marvis. Everything here runs through the user-granted Accessibility permission.
 * Note: Android does not let ANY third-party app flip Wi-Fi/Bluetooth/mobile-data APIs directly on modern versions,
 * so toggles open the relevant Settings panel and click the real switch, like a human would.
 */
class MarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: MarvisAccessibilityService? = null
        /** Package of the app currently on screen (ignores SystemUI), used as context for the AI. */
        @Volatile var currentPackage: String? = null
        val isRunning get() = instance != null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && !pkg.startsWith("com.android.systemui")) currentPackage = pkg
        }
    }
    override fun onInterrupt() = Unit

    /** App swiped from Recents: stop everything and refuse further actions until the app is opened again. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        com.marvis.ai.MarvisRuntime.shutdown(this)
        super.onTaskRemoved(rootIntent)
    }

    // ---------- Global buttons ----------
    fun global(action: String): Boolean = when (action) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }

    // ---------- Scroll / tap / type ----------
    suspend fun scroll(down: Boolean): Boolean {
        val target = rootInActiveWindow?.let { findFirst(it) { n -> n.isScrollable } }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (target != null && target.performAction(action)) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (from, to) = if (down) dm.heightPixels * 0.72f to dm.heightPixels * 0.28f
                         else dm.heightPixels * 0.28f to dm.heightPixels * 0.72f
        return gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 350)
    }

    suspend fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    fun clickByLabel(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val ordered = nodes.sortedByDescending {
                it.text?.toString().equals(label, true) || it.contentDescription?.toString().equals(label, true)
            }
            for (n in ordered) if (clickNode(n)) return true
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent; hops++
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirst(root) { it.isEditable } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- Reading the screen ----------
    fun dumpScreenText(limit: Int = 6000): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > limit) return
            if (n.isVisibleToUser) {
                val t = n.text?.toString() ?: n.contentDescription?.toString()
                if (!t.isNullOrBlank()) sb.append(t.trim()).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString().take(limit)
    }

    /** Full-screen screenshot (API 30+). Black for FLAG_SECURE windows (banking apps, etc.). */
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (cont.isActive) cont.resume(bmp)
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }

    // ---------- Settings switches (Wi-Fi / Bluetooth / mobile data) ----------
    suspend fun toggleSwitch(intent: Intent, labels: List<String>, desired: Boolean?): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        var sw: AccessibilityNodeInfo? = null
        for (i in 0 until 20) {
            delay(250)
            sw = rootInActiveWindow?.let { findSwitch(it, labels) }
            if (sw != null) break
        }
        val node = sw ?: return Outcome(false, "Couldn't find the switch; this phone's Settings layout may differ.")
        val wasOn = node.isChecked
        if (desired == null || desired != wasOn) {
            if (!clickNode(node)) return Outcome(false, "Found the switch but couldn't press it.")
            delay(500)
        }
        delay(300)
        performGlobalAction(GLOBAL_ACTION_BACK)
        val nowOn = desired ?: !wasOn
        return Outcome(true, if (nowOn) "switched on" else "switched off")
    }

    private fun findSwitch(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                var p: AccessibilityNodeInfo? = n
                var depth = 0
                while (p != null && depth < 4) {
                    findFirst(p) { it.isCheckable }?.let { return it }
                    p = p.parent; depth++
                }
            }
        }
        return findFirst(root) { it.isCheckable }
    }

    private fun findFirst(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findFirst(c, pred)?.let { return it }
        }
        return null
    }
}
