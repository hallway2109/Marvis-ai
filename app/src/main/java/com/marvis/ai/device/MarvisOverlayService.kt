package com.marvis.ai.device

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.marvis.ai.MainActivity
import com.marvis.ai.MarvisApp
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.ai.BrainState
import com.marvis.ai.ai.Emotion
import com.marvis.ai.ai.Role
import com.marvis.ai.voice.Chime
import com.marvis.ai.voice.MarvisVoiceService
import com.marvis.ai.voice.VoiceBus
import com.marvis.ai.voice.VoiceMode
import com.marvis.ai.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the orb is showing; mirrors the in-app orb. */
enum class OrbVisual { OFF, MUTED, WAKE, LISTENING, THINKING, SPEAKING }

/** One bubble on the arc menu. */
class ArcItem(val label: String, val action: () -> Unit)

/**
 * Floating minimalist orb over every app (SYSTEM_ALERT_WINDOW): a compact ORB with a LIVE PROMPT BOX that appears under it.
 *  - Live prompt box (between the orb and the mic): shows what you are saying right now, what you just said, and what
 *    Marvis is answering. It fades away on its own. The secret code is never shown.
 *  - Tap the orb: mic ON/OFF with a chime (ON also starts a command at once). While Marvis is thinking or talking,
 *    a tap means "stop".
 *  - Long press: the orb's rings pause and a circular ARC MENU opens around the touch point.
 *  - Drag: moves the whole stack; the position is remembered.
 */
class MarvisOverlayService : Service() {

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 9
        private const val ORB_DP = 108                      // larger orb
        private const val BOX_WIDTH_DP = 220
        private const val LONG_PRESS_MS = 520L
        private const val USER_LINE_MS = 15_000L
        private const val BOT_LINE_MS = 14_000L
        const val PREFS = "marvis_prefs"

        fun start(ctx: Context) {
            if (!MarvisRuntime.active) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MarvisOverlayService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MarvisOverlayService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as MarvisApp
    private var ready = false
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var orb: OrbBall
    private lateinit var box: PromptBox
    private lateinit var params: WindowManager.LayoutParams
    private var orbPx = 0
    private var arc: ArcMenuView? = null

    private var lastB: BrainState? = null
    private var lastV: VoiceState? = null
    private var userSeenId = -1L
    private var userSeenAt = 0L
    private var botSeenId = -1L
    private var botSeenAt = 0L
    private val renderRunnable = Runnable { renderBox() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this) || !MarvisRuntime.active) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Marvis orb", NotificationManager.IMPORTANCE_MIN))
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) { stopSelf(); return }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        orbPx = (ORB_DP * dp).toInt()
        val boxPx = (BOX_WIDTH_DP * dp).toInt()

        orb = OrbBall(this).apply { setOnTouchListener(TouchHandler(tapEnabled = true)) }
        box = PromptBox(this).apply { visibility = View.GONE; setOnTouchListener(TouchHandler(tapEnabled = false)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(orb, LinearLayout.LayoutParams(orbPx, orbPx))
            addView(box, LinearLayout.LayoutParams(boxPx, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt() })
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlay_x", 20)
            y = prefs.getInt("overlay_y", 200)
        }
        wm.addView(root, params)
        ready = true

        scope.launch {
            combine(app.brain.state, VoiceBus.state, VoiceBus.micOn) { b, v, m -> Triple(b, v, m) }
                .collect { (b, v, m) ->
                    val visual = when {
                        b.thinking && !b.speaking -> OrbVisual.THINKING
                        b.speaking -> OrbVisual.SPEAKING
                        v.mode == VoiceMode.OFF -> OrbVisual.OFF
                        !m -> OrbVisual.MUTED
                        v.mode == VoiceMode.COMMAND -> OrbVisual.LISTENING
                        else -> OrbVisual.WAKE
                    }
                    orb.visual = visual
                    orb.emotion = b.emotion
                    orb.level = v.level
                    lastB = b
                    lastV = v
                    renderBox()
                }
        }
        scope.launch { app.settings.liveBox.collect { renderBox() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ready || !MarvisRuntime.active || !app.settings.overlayOn.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Swiped from Recents: remove the orb and shut everything down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        MarvisRuntime.shutdown(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        dismissArc()
        if (ready) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    // ---------------- live prompt box ----------------

    /** Builds the two lines (what you say / what Marvis says) from the latest state and shows or hides the box. */
    private fun renderBox() {
        main.removeCallbacks(renderRunnable)
        if (!ready) return
        val b = lastB
        val v = lastV
        if (b == null || v == null || !app.settings.liveBox.value) { showBox(null, null, 0); return }

        val now = System.currentTimeMillis()
        val chat = b.messages.filter { it.role != Role.SYSTEM }
        val lastUser = chat.lastOrNull { it.role == Role.USER }
        val lastBot = chat.lastOrNull { it.role == Role.ASSISTANT }
        if (lastUser != null && lastUser.id != userSeenId) { userSeenId = lastUser.id; userSeenAt = now }
        if (lastBot != null && lastBot.id != botSeenId) { botSeenId = lastBot.id; botSeenAt = now }

        val secret = app.brain.awaitingSecret.value
        val user: String? = when {
            secret -> "Say your secret code now"                      // the code itself is never displayed
            v.mode == VoiceMode.COMMAND && v.partial.isNotBlank() -> v.partial
            v.mode == VoiceMode.COMMAND -> "Listening…"
            lastUser != null && now - userSeenAt < USER_LINE_MS -> lastUser.text
            else -> null
        }
        val bot: String? = when {
            b.thinking && !b.speaking -> "Thinking…"
            lastBot != null && (b.speaking || now - botSeenAt < BOT_LINE_MS) -> lastBot.text
            else -> null
        }
        showBox(user, bot, b.emotion.argb.toInt())
        if (user != null || bot != null) main.postDelayed(renderRunnable, 1000)   // re-check so it fades away
    }

    private fun showBox(user: String?, bot: String?, botColor: Int) {
        val show = user != null || bot != null
        val wasShown = box.visibility == View.VISIBLE
        if (show) box.update(user, bot, botColor)
        box.visibility = if (show) View.VISIBLE else View.GONE
        if (show != wasShown) runCatching { wm.updateViewLayout(root, params) }
    }

    // ---------------- gestures ----------------

    private fun onTap() {
        dismissArc()
        val brain = app.brain
        val b = brain.state.value
        if (b.thinking || b.speaking) {
            brain.cancelAll()
            Chime.off()
            return
        }
        val voiceOn = VoiceBus.state.value.mode != VoiceMode.OFF
        if (voiceOn && VoiceBus.micOn.value) {
            VoiceBus.micOn.value = false
            Chime.off()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open Marvis once and allow the microphone", Toast.LENGTH_LONG).show()
            return
        }
        Chime.on()
        VoiceBus.micOn.value = true
        app.settings.setAlwaysOn(true)
        val needsStart = !voiceOn
        if (needsStart) MarvisVoiceService.start(this)
        scope.launch {
            if (needsStart) delay(1000)
            VoiceBus.requests.emit(Unit)
        }
    }

    // ---------------- arc menu ----------------

    private fun showArc(cx: Float, cy: Float) {
        dismissArc()
        val brain = app.brain
        val micOn = VoiceBus.micOn.value
        val items = listOf(
            ArcItem("Settings") { openApp("settings") },
            ArcItem("Stop") { brain.cancelAll(); Chime.off() },
            ArcItem(if (micOn) "Mute\nmic" else "Unmute\nmic") {
                VoiceBus.micOn.value = !micOn
                if (micOn) Chime.off() else Chime.on()
            },
            ArcItem("Open\nMarvis") { openApp(null) },
            ArcItem("Hide\norb") { hideOverlay() },
            ArcItem("Quit") { MarvisRuntime.shutdown(this) },
        )
        val v = ArcMenuView(this, cx, cy, items,
            onPick = { item -> dismissArc(); item.action() },
            onDismiss = { dismissArc() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(v, lp) }.onSuccess {
            arc = v
            orb.ringsPaused = true                 // pause / hide the rotating rings while the menu is open
        }
    }

    private fun dismissArc() {
        arc?.let { v -> runCatching { wm.removeView(v) } }
        arc = null
        if (ready) orb.ringsPaused = false
    }

    private fun openApp(action: String?) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.putExtra(MainActivity.EXTRA_ACTION, action)
        runCatching { startActivity(i) }
    }

    private fun hideOverlay() {
        app.settings.setOverlayOn(false)
        Toast.makeText(this, "Marvis orb hidden. Turn it on again in Settings.", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Marvis orb is on")
            .setContentText("Tap: mic on/off. Hold: menu")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /** Tap / long-press / drag for the orb, the box and the mic button (the box ignores taps). */
    private inner class TouchHandler(private val tapEnabled: Boolean) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@MarvisOverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longFired = false

        private val longPress = Runnable { longFired = true; showArc(lastX, lastY) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.rawX; lastY = e.rawY
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && !longFired && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                        dismissArc()
                    }
                    if (moved) {
                        val dm = resources.displayMetrics
                        val w = if (root.width > 0) root.width else orbPx
                        val h = if (root.height > 0) root.height else orbPx
                        params.x = (startX + dx).toInt().coerceIn(0, max(0, dm.widthPixels - w))
                        params.y = (startY + dy).toInt().coerceIn(0, max(0, dm.heightPixels - h))
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) savePosition() else if (!longFired && tapEnabled) onTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}

/** The live text box: a "YOU" line and a "MARVIS" line on a dark rounded panel. */
private class PromptBox(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val userView = TextView(context)
    private val botView = TextView(context)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xE60B1224.toInt())
            cornerRadius = 16 * dp
            setStroke((1.2f * dp).toInt(), 0xAA2EE6D6.toInt())
        }
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        for (t in listOf(userView, botView)) {
            t.textSize = 13f
            t.setTextColor(0xFFE6E9F2.toInt())
            t.ellipsize = TextUtils.TruncateAt.END
            addView(t)
        }
        userView.maxLines = 2
        botView.maxLines = 4
        (botView.layoutParams as LayoutParams).topMargin = (4 * dp).toInt()
    }

    fun update(user: String?, bot: String?, botColor: Int) {
        setLine(userView, "YOU", 0xFF2EE6D6.toInt(), user)
        setLine(botView, "MARVIS", botColor, bot)
    }

    private fun setLine(tv: TextView, label: String, color: Int, text: String?) {
        if (text.isNullOrBlank()) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        val sb = SpannableStringBuilder("$label  $text")
        sb.setSpan(ForegroundColorSpan(color), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sb
    }
}

/**
 * Circular arc / wheel menu. Items sit on a circle around the long-press point; only those facing the screen
 * centre are visible. Dragging rotates the wheel (items follow the finger), release coasts then snaps.
 */
private class ArcMenuView(
    context: Context,
    private val cx: Float,
    private val cy: Float,
    private val items: List<ArcItem>,
    private val onPick: (ArcItem) -> Unit,
    private val onDismiss: () -> Unit,
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val radius = 118f * dp
    private val bubble = 32f * dp
    private val step = (2 * PI / items.size).toFloat()
    private val span = Math.toRadians(105.0).toFloat()          // half-width of the visible arc
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val facing: Float

    private var theta = 0f                                       // wheel rotation
    private var open = 0f                                        // 0..1 open animation
    private var lastAng = 0f
    private var lastT = 0L
    private var omega = 0f                                       // rad per ms
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var settle: ValueAnimator? = null

    private val rect = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val idle = Runnable { onDismiss() }

    init {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val dx = w / 2f - cx
        val dy = h / 2f - cy
        facing = if (hypot(dx, dy) < 80f * dp) (if (cx < w / 2f) 0f else PI.toFloat()) else atan2(dy, dx)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { open = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(idle, 10_000)
    }

    override fun onDetachedFromWindow() {
        settle?.cancel()
        removeCallbacks(idle)
        super.onDetachedFromWindow()
    }

    private fun wrap(a: Float): Float {
        val twoPi = (2 * PI).toFloat()
        var x = a % twoPi
        if (x > PI) x -= twoPi
        if (x < -PI) x += twoPi
        return x
    }

    private fun rel(i: Int) = wrap(i * step + theta)

    private fun posX(i: Int) = (cx + radius * open * cos(facing + rel(i))).coerceIn(bubble, width - bubble)
    private fun posY(i: Int) = (cy + radius * open * sin(facing + rel(i))).coerceIn(bubble, height - bubble)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.argb((110 * open.coerceIn(0f, 1f)).toInt(), 0, 0, 0))
        val teal = 0xFF2EE6D6.toInt()

        // arc track
        val r = radius * open
        rect.set(cx - r, cy - r, cx + r, cy + r)
        track.color = (teal and 0x00FFFFFF) or (0x70 shl 24)
        c.drawArc(rect, Math.toDegrees((facing - span).toDouble()).toFloat(), Math.toDegrees((2 * span).toDouble()).toFloat(), false, track)

        // centre hub
        fill.shader = RadialGradient(cx, cy, 30f * dp, intArrayOf((teal and 0x00FFFFFF) or (0xB0 shl 24), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 30f * dp, fill)
        fill.shader = null
        fill.color = (teal and 0x00FFFFFF) or (0xF0 shl 24)
        c.drawCircle(cx, cy, 6f * dp, fill)

        // bubbles, farthest first so the focused one is on top
        val visible = items.indices.filter { abs(rel(it)) <= span }.sortedByDescending { abs(rel(it)) }
        for (i in visible) {
            val a = abs(rel(i))
            val t = 1f - a / span
            val rr = bubble * (0.72f + 0.38f * t) * open.coerceIn(0f, 1.1f)
            val x = posX(i)
            val y = posY(i)
            val alpha = (90 + 165 * t).toInt().coerceIn(0, 255)

            fill.color = Color.argb((alpha * 0.92f).toInt(), 11, 18, 36)
            c.drawCircle(x, y, rr, fill)
            stroke.strokeWidth = (if (a < step / 2f) 2.6f else 1.5f) * dp
            stroke.color = if (a < step / 2f) Color.argb(alpha, 255, 255, 255) else (teal and 0x00FFFFFF) or (alpha shl 24)
            c.drawCircle(x, y, rr, stroke)

            text.textSize = 11.5f * dp * (0.8f + 0.3f * t)
            text.alpha = alpha
            val lines = items[i].label.split('\n')
            val lh = text.textSize * 1.15f
            var ty = y - (lines.size - 1) * lh / 2f - (text.ascent() + text.descent()) / 2f
            for (ln in lines) { c.drawText(ln, x, ty, text); ty += lh }
        }
    }

    private fun hit(x: Float, y: Float): Int? {
        if (hypot(x - cx, y - cy) < 26f * dp) {                  // centre = the focused bubble
            return items.indices.filter { abs(rel(it)) <= span }.minByOrNull { abs(rel(it)) }
        }
        return items.indices.filter { abs(rel(it)) <= span }.sortedBy { abs(rel(it)) }.firstOrNull { i ->
            val t = 1f - abs(rel(i)) / span
            hypot(x - posX(i), y - posY(i)) <= bubble * (0.72f + 0.38f * t) * 1.15f
        }
    }

    private fun settleWheel() {
        val target = theta + omega * 220f                         // coast a little
        val snapped = (target / step).roundToInt() * step
        settle?.cancel()
        settle = ValueAnimator.ofFloat(theta, snapped).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            addUpdateListener { theta = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        removeCallbacks(idle)
        postDelayed(idle, 10_000)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                downX = e.x; downY = e.y
                lastAng = atan2(e.y - cy, e.x - cx)
                lastT = e.eventTime
                omega = 0f
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) dragging = true
                if (dragging) {
                    val a = atan2(e.y - cy, e.x - cx)
                    val da = wrap(a - lastAng)
                    theta += da                                   // the wheel follows the finger around the centre
                    val dt = max(1L, e.eventTime - lastT)
                    omega = 0.7f * omega + 0.3f * (da / dt)
                    lastAng = a
                    lastT = e.eventTime
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) settleWheel()
                else {
                    val i = hit(e.x, e.y)
                    if (i != null) onPick(items[i]) else onDismiss()
                }
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) settleWheel()
        }
        return true
    }
}

/**
 * Minimal futuristic orb: a soft glow, one hairline ring carrying two bright arcs, and a glass core.
 * Colour follows the state (teal listening for the wake word, white taking a command, violet thinking, amber muted,
 * the emotion colour while speaking) and blends smoothly between them.
 */
private class OrbBall(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val rect = RectF()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var angle = 0f
    private var shown = 0xFF4A5670.toInt()
    private var target = shown
    private var colorAnim: ValueAnimator? = null

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 5000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    var visual: OrbVisual = OrbVisual.OFF
        set(v) { if (field != v) { field = v; retarget() } }
    var emotion: Emotion = Emotion.CALM
        set(v) { if (field != v) { field = v; retarget() } }
    var level: Float = 0f

    /** While the arc menu is open the ring is hidden and paused. */
    var ringsPaused: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            if (v) { if (spinner.isStarted) spinner.pause() } else { if (spinner.isPaused) spinner.resume() }
            invalidate()
        }

    private fun retarget() {
        val c = when (visual) {
            OrbVisual.OFF -> 0xFF4A5670.toInt()
            OrbVisual.MUTED -> 0xFFFFB74D.toInt()
            OrbVisual.WAKE -> 0xFF2EE6D6.toInt()
            OrbVisual.LISTENING -> 0xFFB8FFF5.toInt()
            OrbVisual.THINKING -> 0xFF8E7CFF.toInt()
            OrbVisual.SPEAKING -> emotion.argb.toInt()
        }
        if (c == target) return
        target = c
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), shown, c).apply {
            duration = 350
            addUpdateListener { shown = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); spinner.start() }
    override fun onDetachedFromWindow() { spinner.cancel(); colorAnim?.cancel(); super.onDetachedFromWindow() }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun dark(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.40f
        val col = shown
        val t = Math.toRadians(angle.toDouble()).toFloat()
        val energy = when (visual) {
            OrbVisual.LISTENING -> 0.4f + 0.6f * level
            OrbVisual.SPEAKING -> 0.45f + 0.35f * (sin(t * 5f) * 0.5f + 0.5f)
            OrbVisual.THINKING -> 0.55f
            OrbVisual.WAKE -> 0.22f + 0.12f * sin(t)
            else -> 0.12f
        }
        val rr = r * (1f + 0.07f * energy)
        val spin = if (visual == OrbVisual.THINKING) angle * 3f else angle

        // soft glow
        glow.shader = RadialGradient(cx, cy, rr * 2.3f, intArrayOf(alpha(col, 105), alpha(col, 0)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rr * 2.3f, glow)

        // hairline ring with two bright arcs
        if (!ringsPaused) {
            val rad = rr * 1.5f
            ring.strokeWidth = 1f * dp
            ring.color = alpha(col, 80)
            canvas.drawCircle(cx, cy, rad, ring)
            ring.strokeWidth = 2f * dp
            ring.color = alpha(col, 230)
            rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
            canvas.drawArc(rect, spin, 48f, false, ring)
            canvas.drawArc(rect, spin + 180f, 28f, false, ring)
        }

        // glass core
        core.shader = RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.4f,
            intArrayOf(alpha(0xFFFFFFFF.toInt(), 235), col, dark(col, 0.25f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rr, core)
        core.shader = null
        ring.strokeWidth = 1.2f * dp
        ring.color = alpha(col, 170)
        canvas.drawCircle(cx, cy, rr, ring)
    }
}
