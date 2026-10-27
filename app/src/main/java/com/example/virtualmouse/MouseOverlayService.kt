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
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat

class MouseOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.virtualmouse.START"
        const val ACTION_STOP = "com.example.virtualmouse.STOP"
        const val ACTION_UPDATE_SIZE = "com.example.virtualmouse.UPDATE_SIZE"
        const val ACTION_UPDATE_SENSITIVITY = "com.example.virtualmouse.UPDATE_SENSITIVITY"
        private const val CHANNEL_ID = "virtual_mouse_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var cursorView: ImageView? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var sensitivity = 1.0f
    private var cursorSizeDp = 36

    private val prefs by lazy { getSharedPreferences("vm_prefs", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        loadSettings()
        updateScreenMetrics()
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
            ACTION_UPDATE_SIZE, ACTION_UPDATE_SENSITIVITY -> {
                loadSettings()
                updateCursorSize()
            }
        }
        return START_STICKY
    }

    private fun loadSettings() {
        val sizeIdx = prefs.getInt("mouse_size", 1)
        cursorSizeDp = when (sizeIdx) {
            0 -> 24
            1 -> 36
            2 -> 52
            else -> 40
        }
        sensitivity = prefs.getInt("sensitivity", 100) / 100f
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
        overlayView!!.addView(cursorView, FrameLayout.LayoutParams(sizePx, sizePx))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

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
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        cursorView = null
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

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastTouchX) * sensitivity
                val dy = (event.rawY - lastTouchY) * sensitivity
                cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                positionCursor()
            }
        }
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Virtual Mouse", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Mouse Active")
            .setContentText("Only movement")
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
