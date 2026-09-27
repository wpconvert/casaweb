package com.wpconvert.phoneagent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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

        // Cache the MediaProjection permission token while the app process
        // is still alive. This lets START_STICKY service restarts recover
        // without asking the user for permission again.
        @Volatile
        private var cachedResultCode: Int? = null

        @Volatile
        private var cachedResultData: Intent? = null
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
    // MEDIA PROJECTION STATE
    // =========================

    private var mediaProjection:
        MediaProjection? = null

    private var manualStopRequested =
        false

    private var captureStartInProgress =
        false

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

        Log.d(
            TAG,
            "Service siap; cachedProjection=${cachedResultData != null}"
        )
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
            "onStartCommand action=${intent?.action} startId=$startId"
        )

        when (intent?.action) {

            ACTION_STOP -> {

                manualStopRequested = true

                Log.d(
                    TAG,
                    "ACTION_STOP diterima"
                )

                prefs.edit()
                    .putBoolean(
                        KEY_ACTIVE,
                        false
                    )
                    .apply()

                stopCapture()

                return START_NOT_STICKY
            }

            ACTION_START -> {

                manualStopRequested = false

                Log.d(
                    TAG,
                    "ACTION_START diterima"
                )

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
                    resultCode != Activity.RESULT_OK ||
                    data == null
                ) {

                    Log.e(
                        TAG,
                        "Data MediaProjection tidak valid"
                    )

                    // Do not destroy a currently working capture because
                    // an accidental/duplicate START arrived without data.
                    if (realtimeManager?.isCapturing() == true) {
                        Log.w(
                            TAG,
                            "Capture masih aktif; START tanpa data diabaikan"
                        )
                        return START_STICKY
                    }

                    prefs.edit()
                        .putBoolean(
                            KEY_ACTIVE,
                            false
                        )
                        .apply()

                    return START_STICKY
                }

                cachedResultCode = resultCode
                cachedResultData = data

                startCapture(
                    resultCode,
                    data
                )
            }

            null -> {

                // START_STICKY restart after the service was killed.
                // Reuse the cached MediaProjection token if the process
                // itself is still alive.
                val cachedCode = cachedResultCode
                val cachedData = cachedResultData

                if (
                    !manualStopRequested &&
                    cachedCode != null &&
                    cachedData != null &&
                    realtimeManager == null
                ) {

                    Log.w(
                        TAG,
                        "Service direstart Android; memulihkan capture dari token cached"
                    )

                    startForegroundWithNotification()

                    startCapture(
                        cachedCode,
                        cachedData
                    )
                } else {

                    Log.d(
                        TAG,
                        "START_STICKY restart tanpa token capture; menunggu ACTION_START"
                    )
                }
            }
        }

        // Keep the foreground capture service alive if Android temporarily
        // recreates the service. The explicit STOP path returns NOT_STICKY.
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

        if (captureStartInProgress) {
            Log.d(
                TAG,
                "startCapture sedang berjalan, skip duplicate"
            )
            return
        }

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

        captureStartInProgress = true

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
            // MEDIA PROJECTION HOLDER
            // =========================

            // Keep a reference to the projection for the lifetime of this
            // service. ScreenCapturerAndroid also owns/uses the same token.
            // We intentionally do not call stop() here during transient
            // service cleanup.
            mediaProjection = null

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
            // CHECK CAPTURE
            // =========================

            if (
                !manager.isCapturing()
            ) {

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

                return
            }

            // =========================
            // SCREEN CAPTURE AKTIF
            // =========================

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

            captureStartInProgress = false

            // =========================
            // CLOUDFLARE PUBLISH
            // =========================

            startCloudflarePublishing(
                manager
            )

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

            captureStartInProgress = false
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

            captureStartInProgress = false
            cleanupCapture()
            stopForegroundCompat()
            stopSelf()
        }
    }

    // =========================
    // CLOUDFLARE PUBLISH FLOW
    // =========================

    private fun startCloudflarePublishing(
        manager: RealtimeManager
    ) {

        // =========================
        // DEVICE ID
        // =========================

        val deviceId =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            )

        if (
            deviceId.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "ANDROID_ID tidak tersedia"
            )

            return
        }

        Log.d(
            TAG,
            "Device ID: $deviceId"
        )

        // =========================
        // CREATE SESSION
        // =========================

        Log.d(
            TAG,
            "Membuat Cloudflare session"
        )

        manager.createCloudflareSession {
                success,
                sessionId,
                error ->

            if (
                !success ||
                sessionId.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "Gagal membuat Cloudflare session: $error"
                )

                return@createCloudflareSession
            }

            Log.d(
                TAG,
                "Cloudflare session berhasil: $sessionId"
            )

            // =========================
            // PUBLISH SCREEN
            // =========================

            Log.d(
                TAG,
                "Mengirim screen ke Cloudflare"
            )

            manager.publishToCloudflare(
                sessionId,
                deviceId
            ) {
                publishSuccess,
                answer,
                publishError ->

                if (
                    publishSuccess
                ) {

                    Log.d(
                        TAG,
                        "================================="
                    )

                    Log.d(
                        TAG,
                        "SCREEN BERHASIL DIPUBLISH"
                    )

                    Log.d(
                        TAG,
                        "Device ID: $deviceId"
                    )

                    Log.d(
                        TAG,
                        "Session ID: $sessionId"
                    )

                    Log.d(
                        TAG,
                        "Cloudflare answer berhasil"
                    )

                    Log.d(
                        TAG,
                        "================================="
                    )

                } else {

                    Log.e(
                        TAG,
                        "Publish Cloudflare gagal: $publishError"
                    )
                }
            }
        }
    }

    // =========================
    // STOP CAPTURE
    // =========================

    private fun stopCapture() {

        manualStopRequested = true

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

        Log.w(
            TAG,
            "ScreenCaptureService dihancurkan; manualStop=$manualStopRequested"
        )

        cleanupCapture()

        if (manualStopRequested) {
            cachedResultCode = null
            cachedResultData = null
            mediaProjection = null

            prefs.edit()
                .putBoolean(
                    KEY_ACTIVE,
                    false
                )
                .apply()
        } else {
            // Do NOT erase the cached projection token or mark capture
            // inactive on a transient/system service destruction.
            // START_STICKY can then recreate the service and resume it.
            Log.w(
                TAG,
                "Transient destroy: mempertahankan state capture untuk START_STICKY"
            )
        }

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
