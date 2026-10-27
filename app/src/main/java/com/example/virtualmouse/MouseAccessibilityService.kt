package com.example.virtualmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService used solely to inject click and swipe gestures
 * at the virtual cursor position.
 *
 * Android does not provide a public non-accessibility API to inject
 * clicks into other applications (security restriction). This service
 * is the official supported way.
 *
 * Limitations:
 * - User must explicitly enable the service in system Accessibility settings.
 * - Gestures may be blocked by some system UI or secure windows.
 * - Edge swipes are approximate; success depends on the target app's
 *   gesture handling (e.g. works well on Pixel/MIUI launchers and many ViewPagers).
 */
class MouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not process events; only use gesture APIs.
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Perform a single tap (click) at the given screen coordinates.
     */
    fun performClick(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Perform a linear swipe from (x1,y1) to (x2,y2) lasting durationMs.
     * Used for edge page transitions.
     */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
