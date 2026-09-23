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

    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var brightnessButton: Button
    private lateinit var registerButton: Button

    // WebSocket
    private lateinit var webSocket: WebSocketClientManager

    // WebRTC / Cloudflare Realtime
    private lateinit var realtimeManager: RealtimeManager

    private val captureRequestCode = 1001

    private val handler =
        Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences(
            ScreenCaptureService.PREFS_NAME,
            MODE_PRIVATE
        )
    }

    // Status 1 = registration lifecycle.
    @Volatile
    private var registrationStatus =
        "unregistered"

    private val registrationPoll =
        object : Runnable {

            override fun run() {

                refreshRegistrationStatus()

                handler.postDelayed(
                    this,
                    3000
                )
            }
        }

    private val statusPoll =
        object : Runnable {

            override fun run() {

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

                val registrationText =
                    when (registrationStatus) {
                        "registered" ->
                            "Registered"

                        "requested",
                        "pending" ->
                            "Request Terkirim / Menunggu Approval"

                        else ->
                            "Belum Registered"
                    }

                val captureText =
                    if (active) {
                        "Aktif
${width} × ${height}"
                    } else {
                        "Belum aktif"
                    }

                status.text =
                    "Status 1 — Registration: $registrationText

" +
                    "Status 2 — Screen Capture: $captureText"

                captureButton.text =
                    if (active) {
                        "Hentikan screen capture"
                    } else {
                        "Izinkan akses layar"
                    }

                registerButton.text =
                    when (registrationStatus) {
                        "registered" ->
                            "Sudah Registered"

                        "requested",
                        "pending" ->
                            "Request Terkirim"

                        else ->
                            "Request Register"
                    }

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

        // =========================
        // WEBSOCKET
        // =========================

        android.util.Log.d(
            "WebPhoneAgent",
            "Membuat WebSocketClientManager"
        )

        webSocket =
            WebSocketClientManager(this)

        android.util.Log.d(
            "WebPhoneAgent",
            "Memanggil webSocket.connect()"
        )

        webSocket.connect()

        // =========================
        // WEBRTC / CLOUDFLARE
        // =========================

        android.util.Log.d(
            "WebPhoneAgent",
            "Membuat RealtimeManager"
        )

        realtimeManager =
            RealtimeManager(this)

        android.util.Log.d(
            "WebPhoneAgent",
            "Memanggil realtimeManager.initialize()"
        )

        realtimeManager.initialize()

        // =========================
        // KEEP SCREEN ON
        // =========================

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // =========================
        // UI
        // =========================

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

        val title =
            TextView(this).apply {

                text =
                    "Web Phone Agent"

                textSize =
                    24f
            }

        status =
            TextView(this).apply {

                text =
                    "Status: Menunggu izin screen capture"

                textSize =
                    16f

                setPadding(
                    0,
                    32,
                    0,
                    32
                )
            }

        brightnessButton =
            Button(this).apply {

                text =
                    "Izinkan kontrol brightness"

                setOnClickListener {

                    openWriteSettingsPermission()
                }
            }

        registerButton =
            Button(this).apply {

                text =
                    "Request Register"

                setOnClickListener {
                    requestRegistration()
                }
            }

        captureButton =
            Button(this).apply {

                text =
                    "Izinkan akses layar"

                setOnClickListener {

                    if (
                        prefs.getBoolean(
                            ScreenCaptureService.KEY_ACTIVE,
                            false
                        )
                    ) {

                        android.util.Log.d(
                            "WebPhoneAgent",
                            "Menghentikan screen capture"
                        )

                        val stopIntent =
                            Intent(
                                this@MainActivity,
                                ScreenCaptureService::class.java
                            ).apply {

                                action =
                                    ScreenCaptureService.ACTION_STOP
                            }

                        startService(
                            stopIntent
                        )

                    } else {

                        android.util.Log.d(
                            "WebPhoneAgent",
                            "Meminta izin screen capture"
                        )

                        requestScreenCapturePermission()
                    }
                }
            }

        root.addView(title)

        root.addView(status)

        root.addView(brightnessButton)

        root.addView(registerButton)

        root.addView(captureButton)

        setContentView(root)

        handler.post(
            statusPoll
        )

        handler.post(
            registrationPoll
        )
    }

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

                val connection =
                    URL(
                        "https://web-phone-oneforall.danip4848.workers.dev/api/registration/request"
                    )
                        .openConnection()
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

                val response =
                    JSONObject(
                        responseText
                    )

                val ok =
                    response.optBoolean(
                        "ok",
                        false
                    )

                if (ok) {
                    registrationStatus =
                        response.optString(
                            "registrationStatus",
                            "requested"
                        )
                }

                android.util.Log.d(
                    "WebPhoneAgent",
                    "Registration response: $responseText"
                )

                runOnUiThread {
                    registerButton.isEnabled =
                        true
                }

                connection.disconnect()

            } catch (error: Exception) {

                android.util.Log.e(
                    "WebPhoneAgent",
                    "Gagal Request Register",
                    error
                )

                runOnUiThread {
                    registerButton.isEnabled =
                        true
                }
            }

        }.start()
    }

    private fun refreshRegistrationStatus() {

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
                    return@Thread
                }

                val url =
                    "https://web-phone-oneforall.danip4848.workers.dev/api/registration/status" +
                    "?deviceId=" +
                    Uri.encode(
                        deviceId
                    )

                val connection =
                    URL(url)
                        .openConnection()
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

                if (
                    responseCode !in 200..299
                ) {
                    connection.disconnect()
                    return@Thread
                }

                val responseText =
                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                val response =
                    JSONObject(
                        responseText
                    )

                registrationStatus =
                    response.optString(
                        "registrationStatus",
                        "unregistered"
                    )

                connection.disconnect()

            } catch (error: Exception) {

                android.util.Log.d(
                    "WebPhoneAgent",
                    "Registration status poll gagal: ${error.message}"
                )
            }

        }.start()
    }


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

            startActivity(intent)
        }
    }

    private fun requestScreenCapturePermission() {

        val manager =
            getSystemService(
                MediaProjectionManager::class.java
            )

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            captureRequestCode
        )
    }

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
            requestCode != captureRequestCode
        ) {
            return
        }

        if (
            resultCode != RESULT_OK ||
            data == null
        ) {

            status.text =
                "Status: Izin screen capture ditolak"

            android.util.Log.e(
                "WebPhoneAgent",
                "Izin screen capture ditolak"
            )

            return
        }

        // =========================
        // SCREEN CAPTURE SERVICE
        // =========================

        android.util.Log.d(
            "WebPhoneAgent",
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

        /*
         * PENTING:
         *
         * Jangan memanggil:
         *
         * realtimeManager.startScreenCapture(...)
         *
         * dari Activity.
         *
         * ScreenCaptureService akan menangani
         * MediaProjection setelah foreground
         * service MEDIA_PROJECTION aktif.
         */

        status.text =
            "Status: Memulai screen capture..."
    }

    override fun onDestroy() {

        handler.removeCallbacks(
            statusPoll
        )

        handler.removeCallbacks(
            registrationPoll
        )

        // =========================
        // WEBSOCKET
        // =========================

        webSocket.disconnect()

        // =========================
        // WEBRTC
        // =========================

        realtimeManager.dispose()

        super.onDestroy()
    }
}
