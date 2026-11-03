package com.marvis.ai.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Gives Gemini "eyes".
 * Strategy 1 (preferred, no extra prompt): AccessibilityService.takeScreenshot().
 * Strategy 2 (fallback): a persistent MediaProjection VirtualDisplay started by [ScreenCaptureService].
 * (Android 14+ allows only ONE VirtualDisplay per projection, so we keep it alive and read the latest frame.)
 */
object ScreenCaptureHelper {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var last: Image? = null
    private val lock = Any()

    val hasProjection get() = projection != null

    suspend fun captureJpegBase64(maxSide: Int = 1280, quality: Int = 80): String? {
        val bmp = MarvisAccessibilityService.instance?.screenshot() ?: latestProjectionFrame() ?: return null
        return encode(bmp, maxSide, quality)
    }

    fun attach(ctx: Context, mp: MediaProjection) {
        detach()
        val dm = ctx.resources.displayMetrics
        width = dm.widthPixels; height = dm.heightPixels
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { detach() }
        }, Handler(Looper.getMainLooper()))
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) { last?.close(); last = img }
        }, Handler(Looper.getMainLooper()))
        reader = r
        display = mp.createVirtualDisplay("marvis-eyes", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    fun detach() {
        synchronized(lock) { last?.close(); last = null }
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    private fun latestProjectionFrame(): Bitmap? = synchronized(lock) {
        val img = last ?: return null
        val plane = img.planes[0]
        val rowPad = plane.rowStride - plane.pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPad / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    private fun encode(src: Bitmap, maxSide: Int, quality: Int): String {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
