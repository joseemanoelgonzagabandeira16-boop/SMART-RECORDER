package com.smartteck.smartrecorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class RegionOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlay: FrameLayout? = null
    private var selector: RegionSelectorView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { hideOverlay(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_SHOW) showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlay != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)
        val hint = TextView(this).apply {
            text = "Arraste o retângulo sobre a webcam. Use o canto inferior direito para redimensionar."
            setTextColor(Color.WHITE); textSize = 12f
            setBackgroundColor(0xCC101827.toInt())
            setPadding(18, 14, 18, 14)
        }
        val save = Button(this).apply {
            text = "SALVAR REGIÃO"
            setOnClickListener { selector?.saveRegion(); hideOverlay(); stopSelf() }
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(save, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        val view = RegionSelectorView(this)
        selector = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.bringChildToFront(hint)
        root.bringChildToFront(save)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        try { windowManager?.addView(root, params); overlay = root } catch (_: Exception) { stopSelf() }
    }

    private fun hideOverlay() {
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlay = null; selector = null
    }

    override fun onDestroy() { hideOverlay(); super.onDestroy() }

    private inner class RegionSelectorView(context: Context) : View(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF42D6B5.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3342D6B5 }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF42D6B5.toInt(); style = Paint.Style.FILL }
        private var left = 0f; private var top = 0f; private var right = 0f; private var bottom = 0f
        private var downX = 0f; private var downY = 0f; private var resizing = false
        private val minRegionSizePx = 80f * resources.displayMetrics.density
        private val prefs by lazy { getSharedPreferences("smart_recorder_crop", Context.MODE_PRIVATE) }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (right == 0f) {
                left = w * 0.08f; top = h * 0.18f
                right = w * 0.48f; bottom = h * 0.58f
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(left, top, right, bottom, shade)
            canvas.drawRect(left, top, right, bottom, border)
            canvas.drawCircle(right, bottom, 18f, handle)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    resizing = event.x >= right - 48f && event.y >= bottom - 48f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (resizing) {
                        right = (right + dx).coerceIn(
                            (left + minRegionSizePx).coerceAtMost(width.toFloat()),
                            width.toFloat()
                        )
                        bottom = (bottom + dy).coerceIn(
                            (top + minRegionSizePx).coerceAtMost(height.toFloat()),
                            height.toFloat()
                        )
                    } else {
                        val rw = right - left; val rh = bottom - top
                        left = (left + dx).coerceIn(0f, width - rw); top = (top + dy).coerceIn(0f, height - rh)
                        right = left + rw; bottom = top + rh
                    }
                    downX = event.x; downY = event.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return true
        }

        fun saveRegion() {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            val screenWidth = max(1, metrics.widthPixels).toFloat()
            val screenHeight = max(1, metrics.heightPixels).toFloat()
            // Converte a seleção usando a posição real da View na tela. Isso evita
            // deslocamentos quando o Android inclui barras do sistema ou recortes.
            val screenLocation = IntArray(2)
            getLocationOnScreen(screenLocation)
            val absoluteLeft = (screenLocation[0] + left).coerceIn(0f, screenWidth)
            val absoluteTop = (screenLocation[1] + top).coerceIn(0f, screenHeight)
            val absoluteRight = (screenLocation[0] + right).coerceIn(absoluteLeft, screenWidth)
            val absoluteBottom = (screenLocation[1] + bottom).coerceIn(absoluteTop, screenHeight)
            val normalizedX = (absoluteLeft / screenWidth).coerceIn(0f, 1f)
            val normalizedY = (absoluteTop / screenHeight).coerceIn(0f, 1f)
            val normalizedWidth = ((absoluteRight - absoluteLeft) / screenWidth)
                .coerceIn(0.01f, 1f - normalizedX)
            val normalizedHeight = ((absoluteBottom - absoluteTop) / screenHeight)
                .coerceIn(0.01f, 1f - normalizedY)

            // Mantém a seleção flutuante e a seleção da tela principal sincronizadas.
            // A captura consome as chaves overlay_*; a interface usa webcam_*.
            prefs.edit()
                .putFloat("overlay_x", normalizedX)
                .putFloat("overlay_y", normalizedY)
                .putFloat("overlay_width", normalizedWidth)
                .putFloat("overlay_height", normalizedHeight)
                .putFloat("webcam_x", normalizedX)
                .putFloat("webcam_y", normalizedY)
                .putFloat("webcam_width", normalizedWidth)
                .putFloat("webcam_height", normalizedHeight)
                .putFloat("webcam_size", minOf(normalizedWidth, normalizedHeight))
                .putBoolean("overlay_region_active", true)
                .apply()
        }
    }

    companion object {
        const val ACTION_SHOW = "com.smartteck.smartrecorder.action.SHOW_REGION_OVERLAY"
        const val ACTION_HIDE = "com.smartteck.smartrecorder.action.HIDE_REGION_OVERLAY"
    }
}
