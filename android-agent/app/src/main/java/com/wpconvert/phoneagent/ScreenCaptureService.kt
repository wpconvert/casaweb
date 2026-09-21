package com.wpconvert.phoneagent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class ScreenCaptureService : Service() {

    companion object {

        const val PREFS_NAME =
            "web_phone_agent"

        const val KEY_ACTIVE =
            "screen_capture_active"

        const val KEY_WIDTH =
            "screen_capture_width"

        const val KEY_HEIGHT =
            "screen_capture_height"

        const val KEY_LAST_FRAME_AT =
            "screen_capture_last_frame_at"

        const val ACTION_START =
            "com.wpconvert.phoneagent.START_CAPTURE"

        const val ACTION_STOP =
            "com.wpconvert.phoneagent.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE =
            "result_code"

        const val EXTRA_RESULT_DATA =
            "result_data"

        private const val CHANNEL_ID =
            "web_phone_agent_capture"

        private const val NOTIFICATION_ID =
            1001

        private const val TAG =
            "ScreenCaptureService"
    }

    // =========================
    // HANDLER
    // =========================

    private val serviceHandler =
        Handler(Looper.getMainLooper())

    // =========================
    // WEBRTC
    // =========================

    private var realtimeManager:
        RealtimeManager? = null

    // =========================
    // WAKE LOCK
    // =========================

    private var wakeLock:
        PowerManager.WakeLock? = null

    // =========================
    // PREFS
    // =========================

    private val prefs by lazy {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
    }

    // =========================
    // SERVICE CREATE
    // =========================

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "ScreenCaptureService dibuat"
        )

        createNotificationChannel()
    }

    // =========================
    // START COMMAND
    // =========================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(
            TAG,
            "onStartCommand action=${intent?.action}"
        )

        when (intent?.action) {

            // =========================
            // STOP
            // =========================

            ACTION_STOP -> {

                Log.d(
                    TAG,
                    "ACTION_STOP diterima"
                )

                stopCapture()

                return START_NOT_STICKY
            }

            // =========================
            // START
            // =========================

            ACTION_START -> {

                Log.d(
                    TAG,
                    "ACTION_START diterima"
                )

                /*
                 * PENTING:
                 *
                 * Foreground service harus aktif
                 * SEBELUM RealtimeManager menjalankan
                 * ScreenCapturerAndroid.
                 */

                startForegroundWithNotification()

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        -1
                    )

                val data =
                    if (
                        Build.VERSION.SDK_INT >= 33
                    ) {

                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )

                    } else {

                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA
                        )
                    }

                if (
                    resultCode !=
                    Activity.RESULT_OK ||
                    data == null
                ) {

                    Log.e(
                        TAG,
                        "Data MediaProjection tidak valid"
                    )

                    prefs.edit()
                        .putBoolean(
                            KEY_ACTIVE,
                            false
                        )
                        .apply()

                    stopForegroundCompat()

                    stopSelf()

                    return START_NOT_STICKY
                }

                startCapture(
                    resultCode,
                    data
                )
            }
        }

        return START_STICKY
    }

    // =========================
    // FOREGROUND SERVICE
    // =========================

    private fun startForegroundWithNotification() {

        val notification =
            buildNotification()

        if (
            Build.VERSION.SDK_INT >= 29
        ) {

            Log.d(
                TAG,
                "Memulai foreground service MEDIA_PROJECTION"
            )

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    // =========================
    // START CAPTURE
    // =========================

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {

        if (
            realtimeManager != null &&
            realtimeManager?.isCapturing() == true
        ) {

            Log.d(
                TAG,
                "Screen capture sudah aktif"
            )

            return
        }

        Log.d(
            TAG,
            "Menyiapkan screen capture"
        )

        try {

            // =========================
            // DISPLAY INFO
            // =========================

            val metrics =
                resources.displayMetrics

            val width =
                metrics.widthPixels

            val height =
                metrics.heightPixels

            Log.d(
                TAG,
                "Display: ${width}x${height}"
            )

            prefs.edit()
                .putInt(
                    KEY_WIDTH,
                    width
                )
                .putInt(
                    KEY_HEIGHT,
                    height
                )
                .apply()

            // =========================
            // CPU WAKE LOCK
            // =========================

            acquireScreenWakeLock()

            // =========================
            // REALTIME MANAGER
            // =========================

            Log.d(
                TAG,
                "Membuat RealtimeManager"
            )

            val manager =
                RealtimeManager(
                    applicationContext
                )

            realtimeManager =
                manager

            // =========================
            // INITIALIZE WEBRTC
            // =========================

            Log.d(
                TAG,
                "Inisialisasi WebRTC"
            )

            manager.initialize()

            // =========================
            // START WEBRTC CAPTURE
            // =========================

            /*
             * PENTING:
             *
             * Kita TIDAK memanggil
             * MediaProjectionManager.getMediaProjection()
             * di service ini.
             *
             * RealtimeManager akan membuat
             * ScreenCapturerAndroid.
             *
             * Pada saat ini foreground service
             * MEDIA_PROJECTION sudah aktif.
             */

            Log.d(
                TAG,
                "Memulai WebRTC screen capture"
            )

            manager.startScreenCapture(
                resultCode,
                data
            )

            // =========================
            // PEER CONNECTION
            // =========================

            Log.d(
                TAG,
                "Membuat PeerConnection"
            )

            manager.createPeerConnection()

            // =========================
            // STATUS
            // =========================

            if (
                manager.isCapturing()
            ) {

                prefs.edit()
                    .putBoolean(
                        KEY_ACTIVE,
                        true
                    )
                    .putLong(
                        KEY_LAST_FRAME_AT,
                        System.currentTimeMillis()
                    )
                    .apply()

                Log.d(
                    TAG,
                    "WebRTC screen capture AKTIF"
                )

            } else {

                Log.e(
                    TAG,
                    "WebRTC screen capture gagal aktif"
                )

                prefs.edit()
                    .putBoolean(
                        KEY_ACTIVE,
                        false
                    )
                    .apply()

                cleanupCapture()

                stopForegroundCompat()

                stopSelf()
            }

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SecurityException saat memulai WebRTC capture",
                e
            )

            prefs.edit()
                .putBoolean(
                    KEY_ACTIVE,
                    false
                )
                .apply()

            cleanupCapture()

            stopForegroundCompat()

            stopSelf()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal memulai screen capture",
                e
            )

            prefs.edit()
                .putBoolean(
                    KEY_ACTIVE,
                    false
                )
                .apply()

            cleanupCapture()

            stopForegroundCompat()

            stopSelf()
        }
    }

    // =========================
    // STOP CAPTURE
    // =========================

    private fun stopCapture() {

        Log.d(
            TAG,
            "Menghentikan screen capture"
        )

        prefs.edit()
            .putBoolean(
                KEY_ACTIVE,
                false
            )
            .apply()

        cleanupCapture()

        stopForegroundCompat()

        stopSelf()
    }

    // =========================
    // CLEANUP
    // =========================

    private fun cleanupCapture() {

        Log.d(
            TAG,
            "Cleanup capture"
        )

        try {

            realtimeManager?.stopScreenCapture()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal stop WebRTC capture",
                e
            )
        }

        try {

            realtimeManager?.dispose()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal dispose RealtimeManager",
                e
            )
        }

        realtimeManager =
            null

        releaseWakeLock()
    }

    // =========================
    // WAKE LOCK
    // =========================

    private fun acquireScreenWakeLock() {

        if (
            wakeLock?.isHeld == true
        ) {
            return
        }

        try {

            val powerManager =
                getSystemService(
                    PowerManager::class.java
                )

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "WebPhoneAgent::CaptureWakeLock"
                )

            wakeLock?.setReferenceCounted(
                false
            )

            wakeLock?.acquire()

            Log.d(
                TAG,
                "PARTIAL_WAKE_LOCK aktif"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membuat WakeLock",
                e
            )
        }
    }

    private fun releaseWakeLock() {

        try {

            wakeLock?.let {

                if (
                    it.isHeld
                ) {

                    it.release()
                }
            }

        } catch (_: Exception) {
        }

        wakeLock =
            null
    }

    // =========================
    // NOTIFICATION CHANNEL
    // =========================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT < 26
        ) {
            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Web Phone Agent",
                NotificationManager.IMPORTANCE_LOW
            )

        channel.description =
            "Screen capture Web Phone Agent"

        manager.createNotificationChannel(
            channel
        )
    }

    // =========================
    // NOTIFICATION
    // =========================

    private fun buildNotification(): Notification {

        val openIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (
                    Build.VERSION.SDK_INT >= 23
                ) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                flags
            )

        val builder =
            if (
                Build.VERSION.SDK_INT >= 26
            ) {

                Notification.Builder(
                    this,
                    CHANNEL_ID
                )

            } else {

                Notification.Builder(
                    this
                )
            }

        return builder
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setContentTitle(
                "Web Phone Agent"
            )
            .setContentText(
                "Screen capture aktif"
            )
            .setOngoing(
                true
            )
            .setContentIntent(
                pendingIntent
            )
            .build()
    }

    // =========================
    // STOP FOREGROUND
    // =========================

    private fun stopForegroundCompat() {

        if (
            Build.VERSION.SDK_INT >= 24
        ) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } else {

            @Suppress("DEPRECATION")
            stopForeground(
                true
            )
        }
    }

    // =========================
    // DESTROY
    // =========================

    override fun onDestroy() {

        Log.d(
            TAG,
            "ScreenCaptureService dihancurkan"
        )

        cleanupCapture()

        prefs.edit()
            .putBoolean(
                KEY_ACTIVE,
                false
            )
            .apply()

        super.onDestroy()
    }

    // =========================
    // NOT BOUND
    // =========================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
