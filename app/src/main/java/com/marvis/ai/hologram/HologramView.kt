package com.marvis.ai.hologram

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The interactive hologram: drag to rotate, pinch to zoom, double-tap to reset. It slowly rotates by itself
 * until you touch it. Pure Canvas, no 3D library, so it is light enough to run inside an overlay window.
 */
class HologramView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private var mesh: Mesh = Meshes.reactor()
    private var proj = Projected(mesh.vertexCount)
    var tint: Int = 0xFF2EE6D6.toInt()

    private var yaw = 0.7f
    private var pitch = -0.30f
    private var zoom = 1f
    var autoRotate = true
    private var lastTouch = 0L
    private var scan = 0f
    private var lx = 0f
    private var ly = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (autoRotate && System.currentTimeMillis() - lastTouch > 1800) yaw += 0.012f
            scan = (scan + 0.006f) % 1f
            invalidate()
        }
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(0.5f, 3.2f)
            lastTouch = System.currentTimeMillis()
            return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { reset(); return true }
    })

    fun setSubject(subject: HoloSubject) {
        mesh = Meshes.forKey(subject.key)
        proj = Projected(mesh.vertexCount)
        reset()
    }

    fun reset() { yaw = 0.7f; pitch = -0.30f; zoom = 1f; autoRotate = true; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; lastTouch = System.currentTimeMillis() }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                yaw += (e.x - lx) * 0.01f
                pitch = (pitch + (e.y - ly) * 0.01f).coerceIn(-1.45f, 1.45f)
                lx = e.x; ly = e.y
                lastTouch = System.currentTimeMillis()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(i); ly = e.getY(i)
            }
        }
        return true
    }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.47f
        val base = minOf(w, h) * 0.36f

        // projector base: two flat rings under the model
        line.strokeWidth = 1f * dp
        for ((k, f) in listOf(1.15f, 0.82f).withIndex()) {
            line.color = alpha(tint, if (k == 0) 90 else 140)
            rect.set(cx - base * f, h * 0.90f - base * f * 0.16f, cx + base * f, h * 0.90f + base * f * 0.16f)
            canvas.drawOval(rect, line)
        }

        Holo3D.project(mesh, yaw, pitch, 0f, zoom, cx, cy, base, proj)
        for (pass in 0..1) {                                         // glow pass, then the sharp line
            line.strokeWidth = (if (pass == 0) 3.6f else 1.3f) * dp
            for (i in 0 until mesh.edgeCount) {
                val a = mesh.ea[i]; val b = mesh.eb[i]
                val al = Holo3D.depthAlpha((proj.z[a] + proj.z[b]) / 2f)
                line.color = alpha(tint, ((if (pass == 0) 0.16f else 0.95f) * al * 255).toInt())
                canvas.drawLine(proj.x[a], proj.y[a], proj.x[b], proj.y[b], line)
            }
        }

        // scan band
        val sy = h * scan
        fill.shader = LinearGradient(0f, sy - 22 * dp, 0f, sy + 22 * dp,
            intArrayOf(alpha(tint, 0), alpha(tint, 55), alpha(tint, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, sy - 22 * dp, w, sy + 22 * dp, fill)
        fill.shader = null
    }
}

/** The floating HUD window: title bar, the interactive hologram, analysis bullets, and a footnote. */
class HologramPanel(private val ctx: Context, private val onClose: () -> Unit) {
    private val dp = ctx.resources.displayMetrics.density
    private val holo = HologramView(ctx)
    private val title = TextView(ctx)
    private val bullets = TextView(ctx)
    private val foot = TextView(ctx)
    private val cyan = 0xFF2EE6D6.toInt()

    val view: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xEB040B14.toInt())
            cornerRadius = 18 * dp
            setStroke((1.2f * dp).toInt(), 0x992EE6D6.toInt())
        }
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.apply { setTextColor(cyan); textSize = 13f; typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(chip("AUTO") { holo.autoRotate = !holo.autoRotate })
        header.addView(chip("RESET") { holo.reset() })
        header.addView(chip("X") { onClose() })
        addView(header)

        addView(holo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (250 * dp).toInt()))

        bullets.apply { setTextColor(0xFFD8F7F2.toInt()); textSize = 12f; typeface = Typeface.MONOSPACE; maxLines = 7 }
        addView(bullets, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        foot.apply { setTextColor(0xFF6F7C90.toInt()); textSize = 9.5f; typeface = Typeface.MONOSPACE }
        addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (6 * dp).toInt() })
    }

    private fun chip(label: String, action: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(cyan)
        setPadding((9 * dp).toInt(), (5 * dp).toInt(), (9 * dp).toInt(), (5 * dp).toInt())
        background = GradientDrawable().apply { cornerRadius = 10 * dp; setStroke((1 * dp).toInt(), 0x662EE6D6.toInt()) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = (6 * dp).toInt() }
        setOnClickListener { action() }
    }

    fun show(subject: HoloSubject) {
        holo.setSubject(subject)
        title.text = subject.title.uppercase()
        bullets.text = "› analysing…"
        foot.text = subject.note + "  Drag to rotate, pinch to zoom, double-tap to reset."
    }

    fun details(lines: List<String>) {
        bullets.text = lines.joinToString("\n") { "› $it" }
    }

    fun windowParams(): WindowManager.LayoutParams {
        val dm = ctx.resources.displayMetrics
        return WindowManager.LayoutParams(
            (dm.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.07f).toInt()
        }
    }
}
