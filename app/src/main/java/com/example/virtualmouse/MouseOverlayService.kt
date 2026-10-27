package com.example.virtualmouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Floating overlay service that shows the virtual mouse cursor and captures
 * touch events to move it. Clicks and edge gestures are delegated to
 * MouseAccessibilityService.
 *
 * Limitations (Android security model):
 * - Requires SYSTEM_ALERT_WINDOW (overlay) permission.
 * - Requires AccessibilityService for real click injection (no public API otherwise).
 * - Edge page transitions are best-effort: perform a swipe gesture via Accessibility.
 *   Works reliably on launchers / ViewPagers; not universal across all apps.
 * - Full-screen touch capture can interfere with some system gestures; keep sensitivity moderate.
 */
class MouseOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.virtualmouse.START"
        const val ACTION_STOP = "com.example.virtualmouse.STOP"
        const val ACTION_UPDATE_SIZE = "com.example.virtualmouse.UPDATE_SIZE"
        const val ACTION_UPDATE_SENSITIVITY = "com.example.virtualmouse.UPDATE_SENSITIVITY"
        const val ACTION_UPDATE_EDGE = "com.example.virtualmouse.UPDATE_EDGE"
        private const val CHANNEL_ID = "virtual_mouse_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EDGE_THRESHOLD_PX = 24
        private const val EDGE_HOLD_MS = 350L
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var cursorView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var sensitivity = 1.0f
    private var edgeSwipeEnabled = true
    private var cursorSizeDp = 36

    private var lastEdgeSide: Int = 0 // -1 left, 1 right, 0 none
    private var edgeEnterTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("vm_prefs", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        loadSettings()
        updateScreenMetrics()
        // Start cursor roughly in center
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                showOverlay()
            }
            ACTION_STOP -> {
                hideOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_SIZE, ACTION_UPDATE_SENSITIVITY, ACTION_UPDATE_EDGE -> {
                loadSettings()
                updateCursorSize()
            }
        }
        return START_STICKY
    }

    private fun loadSettings() {
        val sizeIdx = prefs.getInt("mouse_size", 1)
        cursorSizeDp = when (sizeIdx) {
            0 -> 24  // small
            1 -> 36  // medium
            2 -> 52  // large
            else -> prefs.getInt("custom_size_dp", 40)
        }
        sensitivity = prefs.getInt("sensitivity", 100) / 100f
        edgeSwipeEnabled = prefs.getBoolean("edge_swipe", true)
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun showOverlay() {
        if (overlayView != null) return

        // Full-screen transparent touch catcher + cursor child
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            isFocusable = true
        }

        cursorView = ImageView(this).apply {
            setImageResource(R.drawable.cursor_arrow)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val density = resources.displayMetrics.density
        val sizePx = (cursorSizeDp * density).toInt()
        val cursorLp = FrameLayout.LayoutParams(sizePx, sizePx)
        overlayView!!.addView(cursorView, cursorLp)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Touch handling on the full overlay
        overlayView!!.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        try {
            windowManager.addView(overlayView, params)
            positionCursor()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        cursorView = null
        params = null
    }

    private fun updateCursorSize() {
        cursorView?.let { cv ->
            val density = resources.displayMetrics.density
            val sizePx = (cursorSizeDp * density).toInt()
            val lp = cv.layoutParams as FrameLayout.LayoutParams
            lp.width = sizePx
            lp.height = sizePx
            cv.layoutParams = lp
            positionCursor()
        }
    }

    private fun positionCursor() {
        cursorView?.let { cv ->
            val density = resources.displayMetrics.density
            val half = (cursorSizeDp * density) / 2f
            cv.x = cursorX - half
            cv.y = cursorY - half
        }
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var downTime = 0L

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                isDragging = false
                downTime = System.currentTimeMillis()
                lastEdgeSide = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastTouchX) * sensitivity
                val dy = (event.rawY - lastTouchY) * sensitivity
                if (abs(dx) > 2 || abs(dy) > 2) {
                    isDragging = true
                }
                cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                positionCursor()
                checkEdge()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - downTime
                if (!isDragging && duration < 300) {
                    // Treat as click at current cursor position
                    performClickAtCursor()
                }
                lastEdgeSide = 0
            }
        }
        return true
    }

    private fun checkEdge() {
        if (!edgeSwipeEnabled) return
        val now = System.currentTimeMillis()
        val side = when {
            cursorX <= EDGE_THRESHOLD_PX -> -1
            cursorX >= screenWidth - EDGE_THRESHOLD_PX -> 1
            else -> 0
        }
        if (side != 0) {
            if (lastEdgeSide != side) {
                lastEdgeSide = side
                edgeEnterTime = now
            } else if (now - edgeEnterTime >= EDGE_HOLD_MS) {
                // Trigger edge swipe once
                performEdgeSwipe(side)
                edgeEnterTime = Long.MAX_VALUE // prevent repeat until leave
            }
        } else {
            lastEdgeSide = 0
        }
    }

    private fun performClickAtCursor() {
        val service = MouseAccessibilityService.instance
        if (service != null) {
            service.performClick(cursorX.toInt(), cursorY.toInt())
        }
    }

    private fun performEdgeSwipe(side: Int) {
        val service = MouseAccessibilityService.instance
        if (service != null) {
            // Swipe from edge toward center (page turn style)
            val startX = if (side < 0) 10 else screenWidth - 10
            val endX = if (side < 0) screenWidth / 3 else (screenWidth * 2) / 3
            val y = cursorY.toInt().coerceIn(100, screenHeight - 100)
            service.performSwipe(startX, y, endX, y, 250)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Virtual Mouse",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the virtual mouse overlay active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.cursor_arrow)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }
}
