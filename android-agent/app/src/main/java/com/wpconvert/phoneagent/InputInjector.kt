package com.wpconvert.phoneagent

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Non-Accessibility input path.
 *
 * The normal APK process never calls AccessibilityService. Instead, commands
 * are forwarded over a persistent Shizuku UserService binder. When Shizuku is
 * started through wireless debugging/ADB, the UserService runs as shell UID.
 */
object InputInjector {
    private const val TAG = "InputInjector"
    private const val REQUEST_CODE = 4107

    @Volatile
    private var service: IRemoteInputService? = null

    @Volatile
    private var binding = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null) {
                IRemoteInputService.Stub.asInterface(binder)
            } else {
                null
            }
            binding = false
            Log.i(TAG, "Shizuku UserService connected: ${service?.diagnostic()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            Log.e(TAG, "Shizuku UserService disconnected")
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                Log.i(TAG, "Shizuku permission result=$grantResult")
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindService()
                }
            }
        }

    fun initialize(activity: Activity) {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)

            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku is not running")
                return
            }

            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.i(TAG, "Requesting Shizuku permission")
                Shizuku.requestPermission(REQUEST_CODE)
                return
            }

            bindService()
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku initialize failed: ${t.message}", t)
        }
    }

    private fun bindService() {
        if (service != null || binding) return

        try {
            binding = true
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    PrivilegedInputService::class.java.name
                )
            )
                .daemon(true)
                .processNameSuffix("input")
                .version(1)
                .tag("remotephone-input")

            Shizuku.bindUserService(args, connection)
            Log.i(TAG, "Binding Shizuku UserService...")
        } catch (t: Throwable) {
            binding = false
            Log.e(TAG, "Shizuku bind failed: ${t.message}", t)
        }
    }

    fun tryTap(x: Float, y: Float): Boolean {
        return try {
            service?.tap(x, y) == true
        } catch (t: Throwable) {
            Log.e(TAG, "tap failed: ${t.message}", t)
            false
        }
    }

    fun trySwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        return try {
            service?.swipe(x1, y1, x2, y2, durationMs) == true
        } catch (t: Throwable) {
            Log.e(TAG, "swipe failed: ${t.message}", t)
            false
        }
    }

    fun tryBack(): Boolean = tryKey(android.view.KeyEvent.KEYCODE_BACK)

    fun tryHome(): Boolean = tryKey(android.view.KeyEvent.KEYCODE_HOME)

    fun tryRecents(): Boolean = tryKey(android.view.KeyEvent.KEYCODE_APP_SWITCH)

    private fun tryKey(keyCode: Int): Boolean {
        return try {
            service?.key(keyCode) == true
        } catch (t: Throwable) {
            Log.e(TAG, "key failed: ${t.message}", t)
            false
        }
    }

    fun diagnostic(): String {
        return try {
            when {
                !Shizuku.pingBinder() -> "Shizuku OFF"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku permission belum diberikan"
                service == null -> "Shizuku UserService belum terhubung"
                else -> service?.diagnostic() ?: "Shizuku service null"
            }
        } catch (t: Throwable) {
            "Shizuku error: ${t.message}"
        }
    }
}
