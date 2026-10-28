package com.marvis.ai.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import com.marvis.ai.security.LockType
import android.os.PersistableBundle
import android.os.Build
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.ClipData
import android.graphics.PointF
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
    /** Smooth finger swipe first (works in every app); falls back to the node's scroll action. */
    suspend fun scroll(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (from, to) = if (down) dm.heightPixels * 0.68f to dm.heightPixels * 0.30f
                         else dm.heightPixels * 0.30f to dm.heightPixels * 0.68f
        if (gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 480)) return true
        val target = rootInActiveWindow?.let { findFirst(it) { n -> n.isScrollable } }
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target?.performAction(action) == true
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


    // ---------- In-app search and results ----------

    private fun findSearchField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.let { r -> findFirst(r) { it.isEditable && it.isVisibleToUser } }

    /** Types [query] into the current app's search box (opening the search UI first if needed) and submits it. */
    suspend fun searchInCurrentApp(query: String): Boolean {
        var field = findSearchField()
        if (field == null) {
            clickByLabel(listOf("Search", "Search or type URL", "Find", "Search YouTube", "Search Google"))
            delay(900)
            field = findSearchField()
        }
        if (field == null) return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        delay(600)
        val fresh = findSearchField() ?: field
        val submitted = fresh.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!submitted) clickByLabel(listOf("Search", "Go"))
        delay(800)
        return true
    }

    /** Opens the n-th visible result (1 = first): large clickable rows below the app bar, top to bottom. */
    suspend fun openResult(n: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val dm = resources.displayMetrics
        val minTop = dm.heightPixels * 0.13f
        val maxBottom = dm.heightPixels * 0.95f
        val rows = ArrayList<Pair<Rect, AccessibilityNodeInfo>>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.isClickable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top >= minTop && r.bottom <= maxBottom &&
                    r.height() >= dm.heightPixels * 0.07f && r.width() >= dm.widthPixels * 0.45f) rows += r to node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        val sorted = rows.sortedBy { it.first.top }
        val distinct = ArrayList<Pair<Rect, AccessibilityNodeInfo>>()
        for (row in sorted) {
            if (distinct.isEmpty() || row.first.top - distinct.last().first.top > dm.heightPixels * 0.04f) distinct += row
        }
        val pick = distinct.getOrNull(n - 1) ?: return false
        if (pick.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tap(pick.first.exactCenterX(), pick.first.exactCenterY())
    }

    // ---------- Lock screen ----------

    private fun lockRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        for (w in windows) w.root?.let { roots += it }
        rootInActiveWindow?.let { roots += it }
        return roots
    }

    private fun idName(n: AccessibilityNodeInfo): String = n.viewIdResourceName.orEmpty()

    suspend fun swipeUpFromBottom(): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        return gesture(Path().apply { moveTo(x, dm.heightPixels * 0.92f); lineTo(x, dm.heightPixels * 0.35f) }, 380)
    }

    private suspend fun waitFor(timeoutMs: Long, ready: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (ready()) return true
            delay(250)
            waited += 250
        }
        return ready()
    }

    /** One short summary used in error messages: tells apart "screen hidden" from "keys not recognised". */
    fun describeLockScreen(): String {
        var nodes = 0
        fun count(n: AccessibilityNodeInfo?) {
            if (n == null) return
            nodes++
            for (i in 0 until n.childCount) count(n.getChild(i))
        }
        lockRoots().forEach { count(it) }
        return if (nodes < 6) "The lock screen looks hidden from accessibility on this phone."
        else "I could see $nodes items on the lock screen but not the controls."
    }

    // ----- PIN keys -----

    private fun isDigitKey(n: AccessibilityNodeInfo, d: Char): Boolean {
        val txt = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val id = idName(n)
        val lead = Regex("^$d(\\s|$)")
        return txt == d.toString() || desc == d.toString() ||
            (txt.length <= 8 && lead.containsMatchIn(txt)) || (desc.length <= 8 && lead.containsMatchIn(desc)) ||
            id.endsWith("key$d") || id.endsWith("key_$d") || id.endsWith("digit$d") || id.endsWith("pin_key$d")
    }

    private fun findDigitKey(d: Char): AccessibilityNodeInfo? =
        lockRoots().firstNotNullOfOrNull { r -> findFirst(r) { isDigitKey(it, d) } }

    private suspend fun pressNode(node: AccessibilityNodeInfo): Boolean {
        if (clickNode(node)) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        return !r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun pressEnterKey() {
        val hit = lockRoots().firstNotNullOfOrNull { r ->
            findFirst(r) { n ->
                val d = n.contentDescription?.toString().orEmpty()
                val t = n.text?.toString().orEmpty()
                val id = idName(n)
                id.endsWith("key_enter") || id.endsWith("enter_key") || id.endsWith("ok_button") ||
                    listOf("Enter", "OK", "Done", "Confirm").any { l -> d.equals(l, true) || t.equals(l, true) }
            }
        }
        if (hit != null) pressNode(hit)
    }

    // ----- password box -----

    private fun findPasswordField(): AccessibilityNodeInfo? =
        lockRoots().firstNotNullOfOrNull { r ->
            findFirst(r) { n ->
                n.isEditable || n.isPassword ||
                    (idName(n).contains("password", true) && n.className?.toString()?.contains("EditText") == true)
            }
        }

    // ----- pattern pad -----

    private fun gridFrom(r: Rect): List<PointF> {
        val size = minOf(r.width(), r.height()).toFloat()
        val x0 = r.left + (r.width() - size) / 2f
        val y0 = r.top + (r.height() - size) / 2f
        val cell = size / 3f
        return (0 until 9).map { PointF(x0 + (it % 3 + 0.5f) * cell, y0 + (it / 3 + 0.5f) * cell) }
    }

    private fun collect(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        if (pred(n)) out += n
        for (i in 0 until n.childCount) collect(n.getChild(i), pred, out)
    }

    /** The nine dot centres in reading order (1 = top-left ... 9 = bottom-right), or null if the pad isn't visible. */
    private fun patternCenters(): List<PointF>? {
        val dm = resources.displayMetrics
        for (root in lockRoots()) {
            // 1) the pad lists its nine dots as child nodes: use their real positions (most accurate)
            val pads = ArrayList<AccessibilityNodeInfo>()
            collect(root, { it.childCount == 9 }, pads)
            for (pad in pads) {
                val pts = ArrayList<PointF>()
                for (i in 0 until 9) {
                    val c = pad.getChild(i) ?: break
                    val r = Rect()
                    c.getBoundsInScreen(r)
                    if (r.isEmpty) break
                    pts += PointF(r.exactCenterX(), r.exactCenterY())
                }
                if (pts.size == 9) return pts.sortedBy { it.y }.chunked(3).flatMap { row -> row.sortedBy { it.x } }
            }

            // 2) the pad itself, found by id, class or (English) description; the biggest one wins
            val hosts = ArrayList<AccessibilityNodeInfo>()
            collect(root, { n ->
                idName(n).contains("pattern", true) || n.className?.toString()?.contains("LockPatternView") == true ||
                    n.contentDescription?.toString()?.contains("pattern", true) == true
            }, hosts)
            val host = hosts.maxByOrNull { n -> val r = Rect(); n.getBoundsInScreen(r); r.width().toLong() * r.height() }
            if (host != null) {
                val r = Rect()
                host.getBoundsInScreen(r)
                if (r.width() > dm.widthPixels * 0.3f && r.height() > dm.widthPixels * 0.3f) return gridFrom(r)
            }

            // 3) any large, square-ish, empty view in the lower part of the screen (works when labels are translated)
            val views = ArrayList<AccessibilityNodeInfo>()
            collect(root, { n ->
                n.className?.toString() == "android.view.View" && n.text == null && n.contentDescription == null
            }, views)
            val guess = views.filter { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                val w = r.width().toFloat()
                val h = r.height().toFloat()
                w > dm.widthPixels * 0.35f && w < dm.widthPixels * 0.95f && h > 0 && w / h in 0.85f..1.15f &&
                    r.top > dm.heightPixels * 0.2f
            }.maxByOrNull { n -> val r = Rect(); n.getBoundsInScreen(r); r.width().toLong() * r.height() }
            if (guess != null) {
                val r = Rect()
                guess.getBoundsInScreen(r)
                return gridFrom(r)
            }
        }
        return null
    }

    // ----- public: is the code-entry UI on screen yet? -----

    suspend fun credentialUiReady(type: LockType, waitMs: Long): Boolean = when (type) {
        LockType.PIN -> waitFor(waitMs) { findDigitKey('5') != null }
        LockType.PASSWORD -> waitFor(waitMs) { findPasswordField() != null }
        LockType.PATTERN -> waitFor(waitMs) { patternCenters() != null }
        LockType.SWIPE -> true
    }

    // ----- public: enter the code (call only after credentialUiReady) -----

    suspend fun enterPin(pin: String): Outcome {
        for (d in pin) {
            val key = findDigitKey(d) ?: return Outcome(false, "I lost the keypad while typing the PIN")
            if (!pressNode(key)) return Outcome(false, "I couldn't press the $d key")
            delay(170)
        }
        delay(300)
        pressEnterKey()
        return Outcome(true, "PIN entered")
    }

    suspend fun enterPassword(pw: String): Outcome {
        val field = findPasswordField() ?: return Outcome(false, "I lost the password box")
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(250)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pw) }
        var ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) ok = pasteInto(field, pw)                    // some lock screens refuse set-text but accept paste
        if (!ok) return Outcome(false, "the lock screen wouldn't accept typed text")
        delay(350)
        val box = findPasswordField() ?: field
        if (!box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) pressEnterKey()
        return Outcome(true, "password entered")
    }

    /** Fallback only: puts the password on the clipboard (marked sensitive), pastes it, then clears the clipboard. */
    private suspend fun pasteInto(field: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        return try {
            val clip = ClipData.newPlainText("", text)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            cm.setPrimaryClip(clip)
            val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(500)
            pasted
        } finally {
            runCatching { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() }
        }
    }

    /** [seq] = dots 1..9 in reading order. */
    suspend fun drawPattern(seq: List<Int>): Outcome {
        if (seq.size < 2) return Outcome(false, "no pattern is saved")
        val centers = patternCenters() ?: return Outcome(false, "I lost the pattern pad")
        val path = Path()
        seq.forEachIndexed { i, dot ->
            val c = centers[(dot - 1).coerceIn(0, 8)]
            if (i == 0) path.moveTo(c.x, c.y) else path.lineTo(c.x, c.y)
        }
        val ok = gesture(path, 260L * seq.size)
        return if (ok) Outcome(true, "pattern drawn") else Outcome(false, "the lock screen blocked the touch gesture")
    }

    // ---------- Chat helpers (WhatsApp and similar) ----------

    /** Clicks the row showing [text] (a chat or contact), ignoring the search box that also contains it. */
    fun clickRowWithText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = root.findAccessibilityNodeInfosByText(text).filter { !it.isEditable }
        val exact = hits.firstOrNull { it.text?.toString().equals(text, true) }
        for (n in listOfNotNull(exact) + hits) if (clickNode(n)) return true
        return false
    }

    /** Types into the message box (hint or description mentions "message", otherwise the last text field on screen). */
    fun typeInMessageBox(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val fields = ArrayList<AccessibilityNodeInfo>()
        collect(root, { it.isEditable && it.isVisibleToUser }, fields)
        val box = fields.firstOrNull { f ->
            listOf(f.text, f.contentDescription, f.hintText).any { it?.toString()?.contains("message", true) == true }
        } ?: fields.lastOrNull() ?: return false
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
