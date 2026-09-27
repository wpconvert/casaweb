package com.wpconvert.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class RemoteAccessibilityService :
    AccessibilityService() {

    companion object {

        private const val TAG =
            "RemoteAccessibility"

        @Volatile
        private var instance:
            RemoteAccessibilityService? = null

        fun isConnected(): Boolean {
            return instance != null
        }

        fun executeCommand(
            command: JSONObject
        ): Boolean {

            val service =
                instance

            if (service == null) {
                Log.e(
                    TAG,
                    "AccessibilityService belum aktif"
                )
                return false
            }

            return service.handleCommand(
                command
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        try {
            val info = serviceInfo
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            info.feedbackType =
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags =
                info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            setServiceInfo(info)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menerapkan runtime AccessibilityServiceInfo", e)
        }

        instance = this

        Log.d(
            TAG,
            "AccessibilityService CONNECTED - gesture input siap"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // Tidak perlu memproses event.
    }

    override fun onInterrupt() {
        Log.d(
            TAG,
            "AccessibilityService interrupted"
        )
    }

    override fun onDestroy() {

        if (instance === this) {
            instance = null
        }

        Log.d(
            TAG,
            "AccessibilityService destroyed"
        )

        super.onDestroy()
    }

    // =====================================================
    // COMMAND ROUTER
    // =====================================================

    private fun handleCommand(
        json: JSONObject
    ): Boolean {

        return try {

            when (
                json.optString(
                    "type"
                )
            ) {

                "keyevent", "key", "button" ->
                    handleKeyCommand(json)

                "back" ->
                    performGlobalAction(
                        GLOBAL_ACTION_BACK
                    )

                "home" ->
                    performGlobalAction(
                        GLOBAL_ACTION_HOME
                    )

                "recents" ->
                    performGlobalAction(
                        GLOBAL_ACTION_RECENTS
                    )

                "tap" ->
                    tap(
                        json
                    )

                "swipe" ->
                    swipe(
                        json
                    )

                "long_press" ->
                    longPress(
                        json
                    )

                else -> {
                    Log.w(
                        TAG,
                        "Command tidak dikenal: $json"
                    )
                    false
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Command gagal",
                e
            )

            false
        }
    }

    // =====================================================
    // KEY COMMAND
    // =====================================================

    private fun handleKeyCommand(
        json: JSONObject
    ): Boolean {

        return when (json.optString("key").uppercase()) {
            "KEYCODE_BACK", "BACK" ->
                performGlobalAction(GLOBAL_ACTION_BACK)

            "KEYCODE_HOME", "HOME" ->
                performGlobalAction(GLOBAL_ACTION_HOME)

            "KEYCODE_APP_SWITCH",
            "KEYCODE_RECENTS",
            "APP_SWITCH",
            "RECENTS" ->
                performGlobalAction(GLOBAL_ACTION_RECENTS)

            else -> {
                Log.w(TAG, "Key command tidak dikenal: $json")
                false
            }
        }
    }

    // =====================================================
    // TAP
    // =====================================================

    private fun tap(
        json: JSONObject
    ): Boolean {

        val x =
            json.optDouble(
                "x",
                -1.0
            )

        val y =
            json.optDouble(
                "y",
                -1.0
            )

        if (
            x < 0 ||
            y < 0
        ) {
            return false
        }

        Log.d(TAG, "REMOTE TAP x=$x y=$y")

        return dispatchTap(
            x.toFloat(),
            y.toFloat()
        )
    }

    // =====================================================
    // LONG PRESS
    // =====================================================

    private fun longPress(
        json: JSONObject
    ): Boolean {

        val x =
            json.optDouble(
                "x",
                -1.0
            )

        val y =
            json.optDouble(
                "y",
                -1.0
            )

        if (
            x < 0 ||
            y < 0
        ) {
            return false
        }

        return dispatchGesture(
            x.toFloat(),
            y.toFloat(),
            x.toFloat(),
            y.toFloat(),
            700
        )
    }

    // =====================================================
    // SWIPE
    // =====================================================

    private fun swipe(
        json: JSONObject
    ): Boolean {

        val x1 =
            json.optDouble(
                "x1",
                -1.0
            )

        val y1 =
            json.optDouble(
                "y1",
                -1.0
            )

        val x2 =
            json.optDouble(
                "x2",
                -1.0
            )

        val y2 =
            json.optDouble(
                "y2",
                -1.0
            )

        val duration =
            json.optLong(
                "duration",
                400L
            )
                .coerceIn(
                    80L,
                    3000L
                )

        if (
            x1 < 0 ||
            y1 < 0 ||
            x2 < 0 ||
            y2 < 0
        ) {
            return false
        }

        Log.d(TAG, "REMOTE SWIPE ($x1,$y1) -> ($x2,$y2) duration=$duration")

        return dispatchGesture(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            duration
        )
    }

    // =====================================================
    // TAP
    // =====================================================

    private fun dispatchTap(
        x: Float,
        y: Float
    ): Boolean {

        return dispatchGesture(
            x,
            y,
            x,
            y,
            80L
        )
    }

    // =====================================================
    // GESTURE
    // =====================================================

    private fun dispatchGesture(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.N
        ) {
            return false
        }

        val path =
            Path().apply {
                moveTo(
                    x1,
                    y1
                )

                lineTo(
                    x2,
                    y2
                )
            }

        val stroke =
            GestureDescription
                .StrokeDescription(
                    path,
                    0,
                    duration
                )

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    stroke
                )
                .build()

        return dispatchGesture(
            gesture,
            object :
                GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription:
                        GestureDescription
                ) {
                    Log.d(
                        TAG,
                        "Gesture completed"
                    )
                }

                override fun onCancelled(
                    gestureDescription:
                        GestureDescription
                ) {
                    Log.d(
                        TAG,
                        "Gesture cancelled"
                    )
                }
            },
            null
        )
    }
}
