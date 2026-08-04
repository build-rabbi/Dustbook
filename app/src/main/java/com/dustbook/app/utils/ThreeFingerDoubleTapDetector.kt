package com.dustbook.app.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Detects the hidden-settings gestures:
 *
 *  1. THREE FINGER DOUBLE TAP - two three-finger taps in a row. Each tap is
 *     measured from the first finger down to the FIRST finger up, so lifting
 *     the fingers one at a time (the natural way to lift) no longer pushes a
 *     good tap past the timeout. Both taps must land within
 *     [DOUBLE_TAP_WINDOW] of each other.
 *  2. THREE FINGER LONG PRESS - three fingers held still for
 *     [LONG_PRESS_TIMEOUT]. This is the reliable fallback: on devices whose
 *     system gestures eat multi-touch taps (three-finger screenshot and
 *     friends) a double tap can be impossible to land, and a held gesture
 *     cannot be mistaken for a swipe by the system.
 *
 * Either gesture fires [onDetected]. Fed from Activity.dispatchTouchEvent so
 * it works over the WebView without ever consuming or delaying normal touches
 * (it is purely observational).
 */
class ThreeFingerDoubleTapDetector(
    slopPx: Int = ViewConfiguration.getTouchSlop() * 3,
    private val onDetected: () -> Unit
) {

    private companion object {
        const val TAP_TIMEOUT = 700L
        const val DOUBLE_TAP_WINDOW = 900L
        const val LONG_PRESS_TIMEOUT = 800L
        const val REQUIRED_FINGERS = 3
    }

    private val slop = slopPx
    private val handler = Handler(Looper.getMainLooper())

    private var downTime = 0L
    private var liftTime = 0L
    private var pointersDown = 0
    private var maxPointers = 0
    private var moved = false
    private var longPressFired = false
    private var startX = FloatArray(10)
    private var startY = FloatArray(10)
    private var lastTapTime = 0L

    private val longPressRunnable = Runnable {
        // Fire only while all three fingers are still down and still still.
        if (!moved && !longPressFired &&
            pointersDown >= REQUIRED_FINGERS &&
            maxPointers >= REQUIRED_FINGERS
        ) {
            longPressFired = true
            onDetected()
        }
    }

    /** Call from dispatchTouchEvent. Never consumes the event. */
    fun onTouchEvent(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                pointersDown = 1
                maxPointers = 1
                moved = false
                longPressFired = false
                liftTime = 0L
                record(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointersDown = ev.pointerCount
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                record(ev)
                // Start the long-press watch the moment all three fingers are
                // resting on the glass.
                if (maxPointers >= REQUIRED_FINGERS) scheduleLongPress()
            }

            MotionEvent.ACTION_MOVE -> {
                if (moved) return
                for (i in 0 until minOf(ev.pointerCount, startX.size)) {
                    val id = ev.getPointerId(i)
                    if (id >= startX.size) continue
                    if (kotlin.math.abs(ev.getX(i) - startX[id]) > slop ||
                        kotlin.math.abs(ev.getY(i) - startY[id]) > slop
                    ) {
                        moved = true
                        cancelLongPress()
                        return
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                // A tap ends at the first finger lifted, not the last. The
                // old code measured to the final ACTION_UP, so a perfectly
                // good double tap became invalid the moment the fingers left
                // the glass one after another.
                if (liftTime == 0L) {
                    liftTime = SystemClock.uptimeMillis()
                    cancelLongPress()
                }
                pointersDown = ev.pointerCount - 1
                if (ev.actionMasked == MotionEvent.ACTION_UP) finishTap()
            }

            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun finishTap() {
        val duration = liftTime - downTime
        val validTap = !moved && !longPressFired &&
            maxPointers >= REQUIRED_FINGERS &&
            duration <= TAP_TIMEOUT

        if (validTap) {
            val now = liftTime
            if (now - lastTapTime in 1..DOUBLE_TAP_WINDOW) {
                lastTapTime = 0L
                onDetected()
            } else {
                lastTapTime = now
            }
        }
        reset()
    }

    private fun record(ev: MotionEvent) {
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id < startX.size) {
                startX[id] = ev.getX(i)
                startY[id] = ev.getY(i)
            }
        }
    }

    private fun scheduleLongPress() {
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
    }

    private fun cancelLongPress() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun reset() {
        cancelLongPress()
        pointersDown = 0
        maxPointers = 0
        moved = false
        longPressFired = false
        liftTime = 0L
    }

    /** Drop any pending long-press callback; call from onDestroy. */
    fun detach() {
        cancelLongPress()
    }
}
