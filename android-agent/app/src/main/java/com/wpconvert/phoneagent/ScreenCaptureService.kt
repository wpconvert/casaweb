package com.wpconvert.phoneagent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var lastFrameAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .apply()

            cleanupCapture()

            // MediaProjection benar-benar dihentikan oleh Android.
            // Kita tidak mencoba membuat sesi baru tanpa consent pengguna.
            stopSelf()
        }
    }

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        acquireCpuWakeLock()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                prefs.edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .apply()

                cleanupCapture()
                releaseCpuWakeLock()

                stopForegroundCompat()
                stopSelf()

                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundWithNotification()

                acquireCpuWakeLock()

                val resultCode = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (
                    resultCode != Activity.RESULT_OK ||
                    data == null
                ) {
                    prefs.edit()
                        .putBoolean(KEY_ACTIVE, false)
                        .apply()

                    stopForegroundCompat()
                    stopSelf()

                    return START_NOT_STICKY
                }

                startCapture(resultCode, data)
            }

            null -> {
                /*
                 * START_STICKY dapat membuat Android membuat
                 * service kembali setelah proses dibunuh.
                 *
                 * Tetapi kita TIDAK membuat MediaProjection baru
                 * di sini karena Android membutuhkan consent pengguna
                 * untuk sesi MediaProjection baru.
                 *
                 * Jadi service dapat hidup kembali, tetapi capture
                 * tidak akan dipalsukan sebagai masih aktif.
                 */

                acquireCpuWakeLock()
            }
        }

        /*
         * Persistent foreground service.
         *
         * Jika proses service dibunuh oleh sistem, Android dapat
         * mencoba membuat service kembali.
         */
        return START_STICKY
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager =
            getSystemService(PowerManager::class.java)
                ?: return

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:WebPhoneAgent"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {
        }

        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
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

    private fun startCapture(
        resultCode: Int,
        data: Intent
    ) {
        if (mediaProjection != null) {
            return
        }

        val manager =
            getSystemService(MediaProjectionManager::class.java)

        val projection =
            manager.getMediaProjection(
                resultCode,
                data
            ) ?: run {
                prefs.edit()
                    .putBoolean(KEY_ACTIVE, false)
                    .apply()

                stopSelf()
                return
            }

        mediaProjection = projection

        projection.registerCallback(
            projectionCallback,
            serviceHandler
        )

        val metrics = resources.displayMetrics

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader?.setOnImageAvailableListener(
            { reader ->

                val image =
                    reader.acquireLatestImage()
                        ?: return@setOnImageAvailableListener

                try {
                    lastFrameAt =
                        System.currentTimeMillis()

                    prefs.edit()
                        .putBoolean(
                            KEY_ACTIVE,
                            true
                        )
                        .putLong(
                            KEY_LAST_FRAME_AT,
                            lastFrameAt
                        )
                        .putInt(
                            KEY_WIDTH,
                            width
                        )
                        .putInt(
                            KEY_HEIGHT,
                            height
                        )
                        .apply()

                } finally {
                    image.close()
                }

            },
            serviceHandler
        )

        virtualDisplay =
            projection.createVirtualDisplay(
                "WebPhoneAgentScreen",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                serviceHandler
            )

        prefs.edit()
            .putBoolean(
                KEY_ACTIVE,
                true
            )
            .putInt(
                KEY_WIDTH,
                width
            )
            .putInt(
                KEY_HEIGHT,
                height
            )
            .apply()
    }

    private fun cleanupCapture() {

        imageReader?.setOnImageAvailableListener(
            null,
            null
        )

        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.let {
            try {
                it.unregisterCallback(
                    projectionCallback
                )
            } catch (_: Exception) {
            }

            try {
                it.stop()
            } catch (_: Exception) {
            }
        }

        mediaProjection = null
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT < 26) {
            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Web Phone Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                "Status Web Phone Agent"
        }

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {

        val openIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                pendingFlags
            )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(
                    this,
                    CHANNEL_ID
                )
            } else {
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setContentTitle(
                "Web Phone Agent"
            )
            .setContentText(
                if (
                    prefs.getBoolean(
                        KEY_ACTIVE,
                        false
                    )
                ) {
                    "Agent aktif"
                } else {
                    "Agent berjalan"
                }
            )
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun stopForegroundCompat() {

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(
                STOP_FOREGROUND_REMOVE
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {
        /*
         * Jangan menghentikan service ketika user
         * menghapus Activity dari Recent Apps.
         *
         * Service tetap dibiarkan berjalan.
         */

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {

        cleanupCapture()
        releaseCpuWakeLock()

        prefs.edit()
            .putBoolean(
                KEY_ACTIVE,
                false
            )
            .apply()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

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
    }
}
