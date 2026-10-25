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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.marvis.ai.MainActivity
import com.marvis.ai.MarvisApp
import com.marvis.ai.MarvisRuntime
import com.marvis.ai.ai.Emotion
import com.marvis.ai.voice.Chime
import com.marvis.ai.voice.MarvisVoiceService
import com.marvis.ai.voice.VoiceBus
import com.marvis.ai.voice.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** What the orb is showing; mirrors the in-app orb. */
enum class OrbVisual { OFF, MUTED, WAKE, LISTENING, THINKING, SPEAKING }

/**
 * Floating sci-fi orb over every app (SYSTEM_ALERT_WINDOW).
 *  - Tap:        mic ON/OFF with a chime (ON also starts a command at once). While Marvis is thinking or talking,
 *                a tap is "stop" instead: it cancels the search/generation immediately.
 *  - Long press: opens the quick menu (Screen share, Settings, Stop, Mute, Hide, Quit).
 *  - Drag:       moves the orb; position is remembered.
 * Colour follows Marvis's emotion while speaking; it dims when muted and turns violet while thinking.
 */
class MarvisOverlayService : Service() {

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 9
        private const val BALL_DP = 132
        private const val LONG_PRESS_MS = 520L
        private const val MENU_TIMEOUT_MS = 7000L
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
    private lateinit var orb: OrbBall
    private lateinit var params: WindowManager.LayoutParams
    private var ballPx = 0
    private var menu: View? = null
    private val dismissMenuRunnable = Runnable { dismissMenu() }

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
        ballPx = (BALL_DP * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        params = WindowManager.LayoutParams(
            ballPx, ballPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlay_x", 20)
            y = prefs.getInt("overlay_y", 260)
        }
        orb = OrbBall(this).apply { setOnTouchListener(TouchHandler()) }
        wm.addView(orb, params)
        ready = true

        // Keep the orb in sync with the in-app orb: thinking / speaking (with emotion colour) / listening / muted.
        scope.launch {
            combine(app.brain.state, VoiceBus.state, VoiceBus.micOn) { b, v, mic -> Triple(b, v, mic) }
                .collect { (b, v, mic) ->
                    orb.visual = when {
                        b.thinking && !b.speaking -> OrbVisual.THINKING
                        b.speaking -> OrbVisual.SPEAKING
                        v.mode == VoiceMode.OFF -> OrbVisual.OFF
                        !mic -> OrbVisual.MUTED
                        v.mode == VoiceMode.COMMAND -> OrbVisual.LISTENING
                        else -> OrbVisual.WAKE
                    }
                    orb.emotion = b.emotion
                    orb.level = v.level
                }
        }
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
        dismissMenu()
        if (ready) runCatching { wm.removeView(orb) }
        super.onDestroy()
    }

    // ---------------- gestures ----------------

    private fun onTap() {
        dismissMenu()
        val brain = app.brain
        val b = brain.state.value
        if (b.thinking || b.speaking) {            // tap while busy = stop
            brain.cancelAll()
            Chime.off()
            return
        }
        val voiceOn = VoiceBus.state.value.mode != VoiceMode.OFF
        if (voiceOn && VoiceBus.micOn.value) {      // mic ON -> OFF
            VoiceBus.micOn.value = false
            Chime.off()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Open Marvis once and allow the microphone", Toast.LENGTH_LONG).show()
            return
        }
        Chime.on()                                  // mic OFF -> ON, and listen for a command right away
        VoiceBus.micOn.value = true
        app.settings.setAlwaysOn(true)
        val needsStart = !voiceOn
        if (needsStart) MarvisVoiceService.start(this)
        scope.launch {
            if (needsStart) delay(1000)
            VoiceBus.requests.emit(Unit)
        }
    }

    private fun showMenu() {
        dismissMenu()
        val dp = resources.displayMetrics.density
        val brain = app.brain
        val shareOn = brain.state.value.screenShare
        val micOn = VoiceBus.micOn.value

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF20B1224.toInt())
                cornerRadius = 18 * dp
                setStroke((1.2f * dp).toInt(), 0xFF2EE6D6.toInt())
            }
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
        }
        box.addView(TextView(this).apply {
            text = "MARVIS"
            setTextColor(0xFF2EE6D6.toInt())
            textSize = 11f
            letterSpacing = 0.3f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        })

        fun item(label: String, action: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding((14 * dp).toInt(), (11 * dp).toInt(), (22 * dp).toInt(), (11 * dp).toInt())
                setOnClickListener { dismissMenu(); action() }
            })
        }
        item("Screen share: " + if (shareOn) "ON" else "OFF") { toggleScreenShare() }
        item("Settings") { openApp("settings") }
        item("Stop") { brain.cancelAll(); Chime.off() }
        item(if (micOn) "Mute mic" else "Unmute mic") { VoiceBus.micOn.value = !micOn; if (micOn) Chime.off() else Chime.on() }
        item("Open Marvis") { openApp(null) }
        item("Hide orb") { hideOverlay() }
        item("Quit Marvis") { MarvisRuntime.shutdown(this) }

        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) dismissMenu()
            false
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val dm = resources.displayMetrics
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val mw = box.measuredWidth
        val mh = box.measuredHeight
        val margin = (8 * dp).toInt()
        lp.x = (params.x + ballPx / 2 - mw / 2).coerceIn(margin, max(margin, dm.widthPixels - mw - margin))
        val below = params.y + ballPx
        lp.y = if (below + mh + margin < dm.heightPixels) below else max(margin, params.y - mh)

        runCatching { wm.addView(box, lp) }.onSuccess {
            menu = box
            main.postDelayed(dismissMenuRunnable, MENU_TIMEOUT_MS)
        }
    }

    private fun dismissMenu() {
        main.removeCallbacks(dismissMenuRunnable)
        menu?.let { v -> runCatching { wm.removeView(v) } }
        menu = null
    }

    private fun toggleScreenShare() {
        val on = !app.brain.state.value.screenShare
        app.brain.setScreenShare(on)
        Toast.makeText(this, "Screen share " + if (on) "ON" else "OFF", Toast.LENGTH_SHORT).show()
        if (on && !MarvisAccessibilityService.isRunning && !ScreenCaptureHelper.hasProjection) {
            openApp("projection")                   // screen-capture consent must be granted from an activity
        }
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

    private inner class TouchHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@MarvisOverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longFired = false

        private val longPress = Runnable { longFired = true; showMenu() }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && !longFired && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                        dismissMenu()
                    }
                    if (moved) {
                        val dm = resources.displayMetrics
                        params.x = (startX + dx).toInt().coerceIn(0, max(0, dm.widthPixels - ballPx))
                        params.y = (startY + dy).toInt().coerceIn(0, max(0, dm.heightPixels - ballPx))
                        wm.updateViewLayout(orb, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) savePosition() else if (!longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}

/** The drawn orb: glowing sphere with inner mesh and rotating tech rings; smooth colour blending between states. */
private class OrbBall(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val rect = RectF()
    private val clip = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.9f * dp }
    private var angle = 0f
    private var shown = 0xFF4A5670.toInt()
    private var target = shown
    private var colorAnim: ValueAnimator? = null

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    var visual: OrbVisual = OrbVisual.OFF
        set(v) { if (field != v) { field = v; retarget() } }
    var emotion: Emotion = Emotion.CALM
        set(v) { if (field != v) { field = v; retarget() } }
    var level: Float = 0f

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
        val r = minOf(width, height) / 2f * 0.5f
        val col = shown
        val t = Math.toRadians(angle.toDouble()).toFloat()
        val energy = when (visual) {
            OrbVisual.LISTENING -> 0.4f + 0.6f * level
            OrbVisual.SPEAKING -> 0.45f + 0.35f * (sin(t * 5f) * 0.5f + 0.5f)
            OrbVisual.THINKING -> 0.55f
            OrbVisual.WAKE -> 0.22f + 0.12f * sin(t)
            else -> 0.12f
        }
        val spin = if (visual == OrbVisual.THINKING) angle * 3f else angle
        val rr = r * (1f + 0.06f * energy)

        glow.shader = RadialGradient(cx, cy, rr * 2.1f,
            intArrayOf(alpha(col, 120), alpha(col, 0)), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rr * 2.1f, glow)

        ring.strokeWidth = 1.6f * dp
        ring.color = alpha(col, 170)
        ring.pathEffect = DashPathEffect(floatArrayOf(rr * 0.22f, rr * 0.14f), 0f)
        canvas.save(); canvas.rotate(spin, cx, cy); canvas.drawCircle(cx, cy, rr * 1.45f, ring); canvas.restore()
        ring.pathEffect = null
        ring.strokeWidth = 2.4f * dp
        ring.color = alpha(col, 230)
        rect.set(cx - rr * 1.7f, cy - rr * 1.7f, cx + rr * 1.7f, cy + rr * 1.7f)
        canvas.drawArc(rect, -spin * 1.5f, 70f, false, ring)
        canvas.drawArc(rect, -spin * 1.5f + 180f, 40f, false, ring)

        body.shader = RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.3f,
            intArrayOf(dark(col, 0.38f), dark(col, 0.07f)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rr, body)

        clip.reset(); clip.addCircle(cx, cy, rr, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        mesh.color = alpha(col, 120 + (70 * energy).toInt())
        for (k in -2..2) {
            val y = cy + k * rr * 0.36f
            val half = sqrt(max(0f, rr * rr - (y - cy) * (y - cy)))
            rect.set(cx - half, y - rr * 0.1f, cx + half, y + rr * 0.1f)
            canvas.drawOval(rect, mesh)
        }
        for (k in 0 until 6) {
            canvas.save()
            canvas.rotate(k * 30f + angle * 0.2f, cx, cy)
            rect.set(cx - rr * 0.42f, cy - rr, cx + rr * 0.42f, cy + rr)
            canvas.drawOval(rect, mesh)
            canvas.restore()
        }
        canvas.restore()

        body.shader = null
        body.color = alpha(col, 235)
        canvas.drawCircle(cx, cy, rr * (0.14f + 0.08f * energy), body)

        ring.strokeWidth = 2f * dp
        ring.color = alpha(col, 230)
        canvas.drawCircle(cx, cy, rr, ring)
    }
}
