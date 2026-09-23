package com.wpconvert.phoneagent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        private const val TAG = "WebPhoneAgent"
        private const val CAPTURE_REQUEST_CODE = 1001
        private const val WORKER_URL =
            "https://web-phone-oneforall.danip4848.workers.dev"
    }

    private lateinit var status: TextView
    private lateinit var registrationStatus: TextView
    private lateinit var captureButton: Button
    private lateinit var brightnessButton: Button
    private lateinit var registerButton

    private lateinit var webSocket: WebSocketClientManager
    private lateinit var realtimeManager: RealtimeManager

    private val handler =
        Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences(
            ScreenCaptureService.PREFS_NAME,
            MODE_PRIVATE
        )
    }

    private val statusPoll =
        object : Runnable {

            override fun run() {

                updateCaptureStatus()

                updateRegistrationStatus()

                handler.postDelayed(
                    this,
                    1000
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        // =====================================================
        // WEBSOCKET
        // =====================================================

        android.util.Log.d(
            TAG,
            "Membuat WebSocketClientManager"
        )

        webSocket =
            WebSocketClientManager(this)

        android.util.Log.d(
            TAG,
            "Memanggil webSocket.connect()"
        )

        webSocket.connect()

        // =====================================================
        // WEBRTC MANAGER
        // =====================================================

        android.util.Log.d(
            TAG,
            "Membuat RealtimeManager"
        )

        realtimeManager =
            RealtimeManager(
                applicationContext
            )

        android.util.Log.d(
            TAG,
            "Memanggil realtimeManager.initialize()"
        )

        realtimeManager.initialize()

        // =====================================================
        // KEEP SCREEN ON
        // =====================================================

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // =====================================================
        // ROOT UI
        // =====================================================

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.TOP

                setPadding(
                    32,
                    48,
                    32,
                    32
                )
            }

        // =====================================================
        // TITLE
        // =====================================================

        val title =
            TextView(this).apply {

                text =
                    "Web Phone Agent"

                textSize =
                    24f
            }

        // =====================================================
        // CAPTURE STATUS
        // =====================================================

        status =
            TextView(this).apply {

                text =
                    "Status: Screen capture tidak aktif"

                textSize =
                    16f

                setPadding(
                    0,
                    24,
                    0,
                    8
                )
            }

        // =====================================================
        // REGISTRATION STATUS
        // =====================================================

        registrationStatus =
            TextView(this).apply {

                text =
                    "Registration: Mengecek..."

                textSize =
                    16f

                setPadding(
                    0,
                    0,
                    0,
                    24
                )
            }

        // =====================================================
        // BRIGHTNESS
        // =====================================================

        brightnessButton =
            Button(this).apply {

                text =
                    "Izinkan kontrol brightness"

                setOnClickListener {

                    openWriteSettingsPermission()
                }
            }

        // =====================================================
        // REGISTER
        // =====================================================

        registerButton =
            Button(this).apply {

                text =
                    "Request Register"

                setOnClickListener {

                    requestRegistration()
                }
            }

        // =====================================================
        // SCREEN CAPTURE
        // =====================================================

        captureButton =
            Button(this).apply {

                text =
                    "Izinkan akses layar"

                setOnClickListener {

                    val active =
                        prefs.getBoolean(
                            ScreenCaptureService.KEY_ACTIVE,
                            false
                        )

                    if (active) {

                        stopScreenCapture()

                    } else {

                        requestScreenCapturePermission()
                    }
                }
            }

        // =====================================================
        // ADD UI
        // =====================================================

        root.addView(
            title
        )

        root.addView(
            status
        )

        root.addView(
            registrationStatus
        )

        root.addView(
            brightnessButton
        )

        root.addView(
            registerButton
        )

        root.addView(
            captureButton
        )

        setContentView(
            root
        )

        // =====================================================
        // START STATUS POLLING
        // =====================================================

        handler.post(
            statusPoll
        )
    }

    // =========================================================
    // CAPTURE STATUS
    // =========================================================

    private fun updateCaptureStatus() {

        val active =
            prefs.getBoolean(
                ScreenCaptureService.KEY_ACTIVE,
                false
            )

        val width =
            prefs.getInt(
                ScreenCaptureService.KEY_WIDTH,
                0
            )

        val height =
            prefs.getInt(
                ScreenCaptureService.KEY_HEIGHT,
                0
            )

        if (active) {

            status.text =
                "Screen capture: AKTIF\n" +
                "${width} × ${height}"

            captureButton.text =
                "Hentikan screen capture"

        } else {

            status.text =
                "Screen capture: TIDAK AKTIF"

            captureButton.text =
                "Izinkan akses layar"
        }
    }

    // =========================================================
    // REGISTRATION STATUS
    // =========================================================

    private fun updateRegistrationStatus() {

        Thread {

            try {

                val deviceId =
                    Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ANDROID_ID
                    )

                if (
                    deviceId.isNullOrBlank()
                ) {

                    runOnUiThread {

                        registrationStatus.text =
                            "Registration: Device ID tidak tersedia"
                    }

                    return@Thread
                }

                val url =
                    URL(
                        "$WORKER_URL/api/registration-status" +
                        "?deviceId=$deviceId"
                    )

                val connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "GET"

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.connectTimeout =
                    10000

                connection.readTimeout =
                    10000

                val responseCode =
                    connection.responseCode

                val responseText =
                    if (
                        responseCode in 200..299
                    ) {

                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    } else {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            ?: ""
                    }

                connection.disconnect()

                if (
                    responseCode !in 200..299 ||
                    responseText.isBlank()
                ) {

                    runOnUiThread {

                        registrationStatus.text =
                            "Registration: Gagal mengecek status"
                    }

                    return@Thread
                }

                val json =
                    JSONObject(
                        responseText
                    )

                val statusValue =
                    json.optString(
                        "registrationStatus",
                        "unregistered"
                    )

                runOnUiThread {

                    when (statusValue) {

                        "registered" -> {

                            registrationStatus.text =
                                "Registration: REGISTERED"

                            registerButton.text =
                                "Sudah Registered"
                        }

                        "pending" -> {

                            registrationStatus.text =
                                "Registration: MENUNGGU APPROVAL"

                            registerButton.text =
                                "Request Terkirim"
                        }

                        else -> {

                            registrationStatus.text =
                                "Registration: BELUM REGISTERED"

                            registerButton.text =
                                "Request Register"
                        }
                    }
                }

            } catch (e: Exception) {

                android.util.Log.e(
                    TAG,
                    "Gagal mengecek registration status",
                    e
                )

                runOnUiThread {

                    registrationStatus.text =
                        "Registration: Gagal mengecek status"
                }
            }

        }.start()
    }

    // =========================================================
    // REQUEST REGISTRATION
    // =========================================================

    private fun requestRegistration() {

        registerButton.isEnabled =
            false

        registerButton.text =
            "Mengirim request..."

        Thread {

            try {

                val deviceId =
                    Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ANDROID_ID
                    )

                if (
                    deviceId.isNullOrBlank()
                ) {

                    throw Exception(
                        "ANDROID_ID tidak tersedia"
                    )
                }

                val body =
                    JSONObject().apply {

                        put(
                            "deviceId",
                            deviceId
                        )

                        put(
                            "name",
                            "Web Phone Agent"
                        )

                        put(
                            "model",
                            Build.MODEL
                        )
                    }.toString()

                val url =
                    URL(
                        "$WORKER_URL/api/registration-request"
                    )

                val connection =
                    url.openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "POST"

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    15000

                connection.doOutput =
                    true

                connection.outputStream.use {

                    it.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connection.responseCode

                val responseText =
                    if (
                        responseCode in 200..299
                    ) {

                        connection.inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    } else {

                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            ?: "HTTP $responseCode"
                    }

                connection.disconnect()

                android.util.Log.d(
                    TAG,
                    "Registration response code: $responseCode"
                )

                android.util.Log.d(
                    TAG,
                    "Registration response: $responseText"
                )

                if (
                    responseCode !in 200..299
                ) {

                    throw Exception(
                        "HTTP $responseCode"
                    )
                }

                val json =
                    JSONObject(
                        responseText
                    )

                val ok =
                    json.optBoolean(
                        "ok",
                        false
                    )

                val registrationState =
                    json.optString(
                        "registrationStatus",
                        json.optString(
                            "status",
                            ""
                        )
                    )

                runOnUiThread {

                    registerButton.isEnabled =
                        true

                    if (ok) {

                        when (
                            registrationState
                        ) {

                            "registered" -> {

                                registrationStatus.text =
                                    "Registration: REGISTERED"

                                registerButton.text =
                                    "Sudah Registered"
                            }

                            "pending" -> {

                                registrationStatus.text =
                                    "Registration: MENUNGGU APPROVAL"

                                registerButton.text =
                                    "Request Terkirim"
                            }

                            else -> {

                                registrationStatus.text =
                                    "Registration: REQUEST TERKIRIM"

                                registerButton.text =
                                    "Request Terkirim"
                            }
                        }

                    } else {

                        registerButton.text =
                            "Request Register"

                        registrationStatus.text =
                            "Registration: Request gagal"
                    }
                }

            } catch (e: Exception) {

                android.util.Log.e(
                    TAG,
                    "Gagal Request Register",
                    e
                )

                runOnUiThread {

                    registerButton.isEnabled =
                        true

                    registerButton.text =
                        "Request Register"

                    registrationStatus.text =
                        "Registration: Gagal request"
                }
            }
        }.start()
    }

    // =========================================================
    // BRIGHTNESS PERMISSION
    // =========================================================

    private fun openWriteSettingsPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.M
        ) {

            val intent =
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS
                ).apply {

                    data =
                        Uri.parse(
                            "package:$packageName"
                        )
                }

            startActivity(
                intent
            )
        }
    }

    // =========================================================
    // REQUEST SCREEN CAPTURE
    // =========================================================

    private fun requestScreenCapturePermission() {

        val manager =
            getSystemService(
                MediaProjectionManager::class.java
            )

        if (manager == null) {

            status.text =
                "Screen capture: MediaProjection tidak tersedia"

            return
        }

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            CAPTURE_REQUEST_CODE
        )
    }

    // =========================================================
    // ACTIVITY RESULT
    // =========================================================

    @Deprecated(
        "Deprecated in Android API, kept for compatibility"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode !=
            CAPTURE_REQUEST_CODE
        ) {

            return
        }

        if (
            resultCode !=
            RESULT_OK ||
            data == null
        ) {

            status.text =
                "Screen capture: IZIN DITOLAK"

            android.util.Log.e(
                TAG,
                "Izin screen capture ditolak"
            )

            return
        }

        android.util.Log.d(
            TAG,
            "Menjalankan ScreenCaptureService"
        )

        val serviceIntent =
            Intent(
                this,
                ScreenCaptureService::class.java
            ).apply {

                action =
                    ScreenCaptureService.ACTION_START

                putExtra(
                    ScreenCaptureService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    ScreenCaptureService.EXTRA_RESULT_DATA,
                    data
                )
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(
                serviceIntent
            )

        } else {

            startService(
                serviceIntent
            )
        }

        status.text =
            "Screen capture: MEMULAI..."
    }

    // =========================================================
    // STOP SCREEN CAPTURE
    // =========================================================

    private fun stopScreenCapture() {

        android.util.Log.d(
            TAG,
            "Menghentikan screen capture"
        )

        val stopIntent =
            Intent(
                this,
                ScreenCaptureService::class.java
            ).apply {

                action =
                    ScreenCaptureService.ACTION_STOP
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(
                stopIntent
            )

        } else {

            startService(
                stopIntent
            )
        }

        status.text =
            "Screen capture: MENGHENTIKAN..."
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        handler.removeCallbacks(
            statusPoll
        )

        try {

            webSocket.disconnect()

        } catch (e: Exception) {

            android.util.Log.e(
                TAG,
                "Gagal disconnect WebSocket",
                e
            )
        }

        /*
         * Jangan menghentikan capture dari Activity.
         *
         * ScreenCaptureService adalah pemilik
         * MediaProjection dan RealtimeManager.
         */

        super.onDestroy()
    }
}
