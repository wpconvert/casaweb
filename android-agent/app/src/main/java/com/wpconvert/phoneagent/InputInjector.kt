package com.wpconvert.phoneagent

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * EXPERIMENTAL: system-wide input injection without AccessibilityService.
 *
 * This intentionally uses Android's hidden InputManager API through reflection.
 * Normal third-party apps normally do NOT have INJECT_EVENTS permission, so this
 * may fail with SecurityException/hidden-API restrictions. The purpose of this
 * class is to test whether the current device/firmware allows the path.
 */
object InputInjector {
    private const val TAG = "InputInjector"
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISHED = 2

    private var initialized = false
    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var initError: String? = null

    private fun init(): Boolean {
        if (initialized) return inputManager != null && injectMethod != null
        initialized = true

        return try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val getInstance = clazz.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val manager = getInstance.invoke(null)

            val method = clazz.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true

            inputManager = manager
            injectMethod = method

            Log.d(TAG, "InputManager reflection initialized")
            true
        } catch (t: Throwable) {
            initError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "InputManager init failed: $initError", t)
            false
        }
    }

    private fun inject(event: android.view.InputEvent): Boolean {
        if (!init()) return false

        return try {
            val result = injectMethod!!.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISHED
            ) as Boolean
            Log.d(TAG, "inject ${event.javaClass.simpleName} result=$result")
            result
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Log.e(
                TAG,
                "inject failed: ${cause.javaClass.simpleName}: ${cause.message}",
                cause
            )
            false
        } finally {
            if (event is MotionEvent) {
                event.recycle()
            }
        }
    }

    fun tryTap(x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0
        )
        down.source = InputDevice.SOURCE_TOUCHSCREEN

        if (!inject(down)) return false

        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_UP,
            x,
            y,
            0
        )
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        return inject(up)
    }

    fun trySwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x1,
            y1,
            0
        )
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        if (!inject(down)) return false

        val steps = 12
        var allOk = true
        for (i in 1 until steps) {
            val fraction = i.toFloat() / steps.toFloat()
            val x = x1 + ((x2 - x1) * fraction)
            val y = y1 + ((y2 - y1) * fraction)
            val eventTime = downTime + ((durationMs * fraction).toLong())
            val move = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                x,
                y,
                0
            )
            move.source = InputDevice.SOURCE_TOUCHSCREEN
            if (!inject(move)) allOk = false
        }

        val upTime = downTime + durationMs
        val up = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_UP,
            x2,
            y2,
            0
        )
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        return inject(up) && allOk
    }

    fun tryKey(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0
        )
        val up = KeyEvent(
            now,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            0
        )
        return inject(down) && inject(up)
    }

    fun tryBack(): Boolean = tryKey(KeyEvent.KEYCODE_BACK)

    fun tryHome(): Boolean = tryKey(KeyEvent.KEYCODE_HOME)

    fun tryRecents(): Boolean = tryKey(KeyEvent.KEYCODE_APP_SWITCH)

    fun diagnostic(): String {
        if (!init()) return "InputManager FAILED: $initError"
        return "InputManager reflection READY; injection permission/device support still unverified"
    }
}
