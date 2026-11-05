
package com.wpconvert.phoneagent

import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Runs inside the Shizuku UserService process.
 * Uses Android InputManager to inject input events.
 */
class PrivilegedInputService : IRemoteInputService.Stub() {

    companion object {
        private const val TAG = "PrivilegedInputService"
        private const val INJECT_MODE_WAIT = 2
    }

    private val lock = Any()

    private var downTime = 0L
    private var touching = false

    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    init {
        initializeInputManager()
    }

    private fun initializeInputManager() {
        try {
            val clazz = Class.forName("android.hardware.input.InputManager")

            val getInstance = clazz.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true

            inputManager = getInstance.invoke(null)

            injectMethod = clazz.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply {
                isAccessible = true
            }

            Log.i(TAG, "InputManager initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "InputManager initialization failed", t)
        }
    }

    private fun inject(event: InputEvent): Boolean {
        return try {
            val manager = inputManager ?: return false
            val method = injectMethod ?: return false

            method.invoke(
                manager,
                event,
                INJECT_MODE_WAIT
            ) as? Boolean ?: false

        } catch (t: Throwable) {
            Log.e(TAG, "Input injection failed", t)
            false
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )

            val downResult = try {
                inject(down)
            } finally {
                down.recycle()
            }

            if (!downResult) return false

            val upTime = SystemClock.uptimeMillis()

            val up = MotionEvent.obtain(
                now,
                upTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )

            return try {
                inject(up)
            } finally {
                up.recycle()
            }
        }
    }

    override fun touchDown(x: Float, y: Float): Boolean {
        synchronized(lock) {
            if (touching) {
                releaseTouchLocked(x, y)
            }

            downTime = SystemClock.uptimeMillis()

            val event = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )

            val result = try {
                inject(event)
            } finally {
                event.recycle()
            }

            touching = result
            return result
        }
    }

    override fun touchMove(x: Float, y: Float): Boolean {
        synchronized(lock) {
            if (!touching) return false

            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                x,
                y,
                0
            )

            return try {
                inject(event)
            } finally {
                event.recycle()
            }
        }
    }

    override fun touchUp(x: Float, y: Float): Boolean {
        synchronized(lock) {
            return releaseTouchLocked(x, y)
        }
    }

    private fun releaseTouchLocked(x: Float, y: Float): Boolean {
        if (!touching) return false

        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x,
            y,
            0
        )

        val result = try {
            inject(event)
        } finally {
            event.recycle()
        }

        touching = false
        downTime = 0L

        return result
    }

    override fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): Boolean {
        synchronized(lock) {
            if (touching) {
                releaseTouchLocked(x1, y1)
            }

            val duration = durationMs.coerceIn(50L, 3000L)
            val start = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(
                start,
                start,
                MotionEvent.ACTION_DOWN,
                x1,
                y1,
                0
            )

            val downResult = try {
                inject(down)
            } finally {
                down.recycle()
            }

            if (!downResult) return false

            val steps = (duration / 16L).toInt().coerceIn(2, 120)

            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val x = x1 + (x2 - x1) * fraction
                val y = y1 + (y2 - y1) * fraction

                val move = MotionEvent.obtain(
                    start,
                    start + (duration * fraction).toLong(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    0
                )

                val moveResult = try {
                    inject(move)
                } finally {
                    move.recycle()
                }

                if (!moveResult) {
                    val cancel = MotionEvent.obtain(
                        start,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        x,
                        y,
                        0
                    )

                    try {
                        inject(cancel)
                    } finally {
                        cancel.recycle()
                    }

                    return false
                }
            }

            val up = MotionEvent.obtain(
                start,
                start + duration,
                MotionEvent.ACTION_UP,
                x2,
                y2,
                0
            )

            return try {
                inject(up)
            } finally {
                up.recycle()
            }
        }
    }

    override fun key(keyCode: Int): Boolean {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()

            val down = KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            )

            if (!inject(down)) return false

            val up = KeyEvent(
                now,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0
            )

            return inject(up)
        }
    }

    override fun diagnostic(): String {
        return try {
            val uid = android.os.Process.myUid()

            when {
                inputManager == null ->
                    "InputManager unavailable; UID=$uid"

                injectMethod == null ->
                    "Injection method unavailable; UID=$uid"

                else ->
                    "InputManager ready; UID=$uid; touching=$touching"
            }
        } catch (t: Throwable) {
            "Diagnostic error: ${t.message}"
        }
    }

    override fun destroy() {
        synchronized(lock) {
            if (touching) {
                releaseTouchLocked(0f, 0f)
            }
        }

        Log.i(TAG, "PrivilegedInputService destroy requested")
    }
}
