package com.wpconvert.phoneagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import java.lang.ref.WeakReference
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

        private const val DIMMED_KEY = "screen_dimmed"
        private const val PREVIOUS_BRIGHTNESS_KEY = "previous_brightness"
        private const val PREVIOUS_BRIGHTNESS_MODE_KEY = "previous_brightness_mode"
        private const val DIM_BRIGHTNESS = 1
        private const val REMOTE_DEVICE_NAME_KEY = "remote_device_name"

        @Volatile
        private var activeInstance: WeakReference<MainActivity>? = null

        fun restoreDimmedFromRemoteInput() {
            activeInstance?.get()?.restoreScreenBrightnessIfDimmed()
        }

        fun updateRemoteDeviceName(name: String?) {
            activeInstance?.get()?.setRemoteDeviceName(name)
        }
    }

    private lateinit var status: TextView
    private lateinit var registrationStatus: TextView
    private lateinit var deviceCodeText: TextView
    private lateinit var deviceModelText: TextView
    private lateinit var deviceNameText: TextView
    private lateinit var captureButton: Button
    private lateinit var brightnessButton: Button
    private lateinit var registerButton: Button

    private lateinit var webSocket: WebSocketClientManager
    private lateinit var realtimeManager: RealtimeManager

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences(
            ScreenCaptureService.PREFS_NAME,
            MODE_PRIVATE
        )
    }

    private val uiPoll = object : Runnable {
        override fun run() {
            updateCaptureStatus()
            updateBrightnessUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private val registrationPoll = object : Runnable {
        override fun run() {
            updateRegistrationStatus()
            handler.postDelayed(this, 10_000L)
        }
    }

    @Volatile
    private var registrationCheckInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 13, 19)
        window.navigationBarColor = Color.rgb(8, 13, 19)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        android.util.Log.d(TAG, "Membuat WebSocketClientManager")
        webSocket = WebSocketClientManager(this)
        webSocket.connect()

        android.util.Log.d(TAG, "Membuat RealtimeManager")
        realtimeManager = RealtimeManager(applicationContext)
        realtimeManager.initialize()

        activeInstance = WeakReference(this)

        buildUi()

        handler.post(uiPoll)
        handler.post(registrationPoll)
    }

    // =========================================================
    // UI
    // =========================================================

    private fun buildUi() {
        val background = Color.rgb(8, 13, 19)
        val surface = Color.rgb(15, 22, 30)
        val surface2 = Color.rgb(20, 29, 39)
        val border = Color.rgb(45, 58, 72)
        val primary = Color.rgb(255, 106, 0)
        val green = Color.rgb(36, 211, 124)
        val text = Color.rgb(245, 248, 252)
        val muted = Color.rgb(153, 166, 181)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(background)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        val brand = TextView(this).apply {
            this.text = "REMOTEPHONE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.18f
        }
        root.addView(brand, lpWrap())

        val title = TextView(this).apply {
            this.text = "Web Phone Agent"
            textSize = 27f
            setTextColor(text)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(title, lpWrap())

        val subtitle = TextView(this).apply {
            this.text = "Android agent untuk remote phone dashboard"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(18))
        }
        root.addView(subtitle, lpWrap())

        // Device identity card
        val identityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            this.background = rounded(surface, border, 14)
        }

        val identityHeader = TextView(this).apply {
            this.text = "DEVICE IDENTITY"
            textSize = 11f
            setTextColor(muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        identityCard.addView(identityHeader, lpWrap())

        deviceCodeText = TextView(this).apply {
            this.text = "Device Code: membaca..."
            textSize = 17f
            setTextColor(text)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(3))
        }
        identityCard.addView(deviceCodeText, lpWrap())

        deviceModelText = TextView(this).apply {
            this.text = "Model: ${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 12f
            setTextColor(muted)
        }
        identityCard.addView(deviceModelText, lpWrap())

        val savedRemoteName = prefs.getString(
            REMOTE_DEVICE_NAME_KEY,
            ""
        ).orEmpty()

        deviceNameText = TextView(this).apply {
            this.text = if (savedRemoteName.isBlank()) {
                "Name: belum diatur"
            } else {
                "Name: $savedRemoteName"
            }
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        }
        identityCard.addView(deviceNameText, lpWrap())

        val deviceId = getAndroidDeviceId()
        deviceCodeText.text =
            if (deviceId.isNullOrBlank()) {
                "Device Code: tidak tersedia"
            } else {
                "Device Code: $deviceId"
            }

        root.addView(
            identityCard,
            lpMatch(bottom = 14)
        )

        // Status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            this.background = rounded(surface, border, 14)
        }

        val statusHeader = TextView(this).apply {
            this.text = "STATUS"
            textSize = 11f
            setTextColor(muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        statusCard.addView(statusHeader, lpWrap())

        status = TextView(this).apply {
            this.text = "Screen capture: TIDAK AKTIF"
            textSize = 14f
            setTextColor(text)
            setPadding(0, dp(8), 0, dp(4))
        }
        statusCard.addView(status, lpWrap())

        registrationStatus = TextView(this).apply {
            this.text = "Registration: MENGECEK..."
            textSize = 13f
            setTextColor(muted)
        }
        statusCard.addView(registrationStatus, lpWrap())

        root.addView(
            statusCard,
            lpMatch(bottom = 18)
        )

        val actionTitle = TextView(this).apply {
            this.text = "ACTIONS"
            textSize = 11f
            setTextColor(muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(2), 0, 0, dp(8))
        }
        root.addView(actionTitle, lpWrap())

        // Brightness
        brightnessButton = actionButton(
            text = "☀  Perizinan Layar",
            accent = primary,
            backgroundColor = surface2
        ) {
            handleBrightnessClick()
        }
        root.addView(
            brightnessButton,
            lpMatch(bottom = 10)
        )

        // Registration
        registerButton = actionButton(
            text = "⌁  Request Register",
            accent = green,
            backgroundColor = surface2
        ) {
            requestRegistration()
        }
        root.addView(
            registerButton,
            lpMatch(bottom = 10)
        )

        // Capture
        captureButton = actionButton(
            text = "▣  Perizinan Akses Layar",
            accent = Color.rgb(87, 159, 255),
            backgroundColor = surface2
        ) {
            val active = prefs.getBoolean(
                ScreenCaptureService.KEY_ACTIVE,
                false
            )

            if (active) {
                stopScreenCapture()
            } else {
                requestScreenCapturePermission()
            }
        }
        root.addView(captureButton, lpMatch())

        val footer = TextView(this).apply {
            this.text = "WebRTC • Cloudflare Realtime • Secure device identity"
            textSize = 11f
            setTextColor(Color.rgb(100, 114, 130))
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
        }
        root.addView(footer, lpMatch())

        scroll.addView(root)
        setContentView(scroll)

        updateCaptureStatus()
        updateBrightnessUi()
    }

    private fun actionButton(
        text: String,
        accent: Int,
        backgroundColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isAllCaps = false
            minHeight = dp(54)
            minimumHeight = dp(54)
            stateListAnimator = null
            elevation = dp(2).toFloat()
            setPadding(dp(16), 0, dp(16), 0)
            this.background = buttonBackground(backgroundColor, accent)
            setOnClickListener { onClick() }
        }
    }

    private fun buttonBackground(
        normalColor: Int,
        accent: Int
    ): StateListDrawable {
        val normal = rounded(normalColor, darken(accent, 0.55f), 13)
        val pressed = rounded(
            blend(normalColor, accent, 0.24f),
            accent,
            13
        )
        val disabled = rounded(
            Color.rgb(30, 36, 44),
            Color.rgb(55, 63, 72),
            13
        )

        return StateListDrawable().apply {
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                disabled
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                pressed
            )
            addState(intArrayOf(), normal)
        }
    }

    private fun rounded(
        fill: Int,
        stroke: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun lpWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun lpMatch(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (bottom > 0) setMargins(0, 0, 0, dp(bottom))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun blend(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun darken(color: Int, amount: Float): Int {
        return blend(color, Color.BLACK, amount.coerceIn(0f, 1f))
    }

    // =========================================================
    // DEVICE IDENTITY
    // =========================================================

    private fun getAndroidDeviceId(): String? {
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )?.trim()?.takeIf { it.isNotEmpty() }
    }

    // =========================================================
    // CAPTURE STATUS
    // =========================================================

    private fun updateCaptureStatus() {
        val active = prefs.getBoolean(
            ScreenCaptureService.KEY_ACTIVE,
            false
        )

        val width = prefs.getInt(
            ScreenCaptureService.KEY_WIDTH,
            0
        )

        val height = prefs.getInt(
            ScreenCaptureService.KEY_HEIGHT,
            0
        )

        if (active) {
            status.text =
                "●  Screen capture AKTIF\n   ${width} × ${height}"
            status.setTextColor(Color.rgb(36, 211, 124))
            captureButton.text = "■  Hentikan Akses Layar"
        } else {
            status.text = "○  Screen capture TIDAK AKTIF"
            status.setTextColor(Color.rgb(153, 166, 181))
            captureButton.text = "▣  Perizinan Akses Layar"
        }
    }

    private fun setRemoteDeviceName(name: String?) {
        val cleanName = name?.trim().orEmpty()
        if (cleanName.isBlank()) return

        prefs.edit()
            .putString(REMOTE_DEVICE_NAME_KEY, cleanName)
            .apply()

        runOnUiThread {
            if (::deviceNameText.isInitialized) {
                deviceNameText.text = "Name: $cleanName"
            }
        }

        android.util.Log.d(
            TAG,
            "Nama phone dari website diterima: $cleanName"
        )
    }

    // =========================================================
    // REGISTRATION STATUS
    // =========================================================

    private fun updateRegistrationStatus() {
        if (registrationCheckInProgress) return

        val deviceId = getAndroidDeviceId()
        if (deviceId.isNullOrBlank()) {
            registrationStatus.text =
                "Registration: Device Code tidak tersedia"
            return
        }

        registrationCheckInProgress = true

        Thread {
            try {
                val url = URL(
                    "$WORKER_URL/api/registration-status?deviceId=$deviceId"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }

                connection.disconnect()

                if (responseCode !in 200..299 || responseText.isBlank()) {
                    runOnUiThread {
                        registrationStatus.text =
                            "Registration: gagal mengecek status"
                    }
                    return@Thread
                }

                val json = JSONObject(responseText)
                val statusValue = json.optString(
                    "registrationStatus",
                    "unregistered"
                )

                runOnUiThread {
                    when (statusValue) {
                        "registered" -> {
                            registrationStatus.text =
                                "●  Registration: REGISTERED"
                            registrationStatus.setTextColor(
                                Color.rgb(36, 211, 124)
                            )
                            registerButton.text = "✓  Sudah Registered"
                            registerButton.isEnabled = false
                        }

                        "pending" -> {
                            registrationStatus.text =
                                "●  Registration: MENUNGGU APPROVAL"
                            registrationStatus.setTextColor(
                                Color.rgb(255, 190, 70)
                            )
                            registerButton.text = "⌁  Request Terkirim"
                            registerButton.isEnabled = true
                        }

                        else -> {
                            registrationStatus.text =
                                "○  Registration: BELUM REGISTERED"
                            registrationStatus.setTextColor(
                                Color.rgb(153, 166, 181)
                            )
                            registerButton.text = "⌁  Request Register"
                            registerButton.isEnabled = true
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
                        "Registration: gagal mengecek status"
                    registrationStatus.setTextColor(
                        Color.rgb(153, 166, 181)
                    )
                }
            } finally {
                registrationCheckInProgress = false
            }
        }.start()
    }

    // =========================================================
    // REQUEST REGISTRATION
    // =========================================================

    private fun requestRegistration() {
        val deviceId = getAndroidDeviceId()

        if (deviceId.isNullOrBlank()) {
            registrationStatus.text =
                "Registration: Device Code tidak tersedia"
            return
        }

        registerButton.isEnabled = false
        registerButton.text = "⌁  Mengirim request..."
        registrationStatus.text = "Registration: MENGIRIM..."

        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", "Web Phone Agent")
                    put("model", Build.MODEL)
                }.toString()

                val url = URL(
                    "$WORKER_URL/api/registration-request"
                )

                val connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: "HTTP $responseCode"
                }

                connection.disconnect()

                android.util.Log.d(
                    TAG,
                    "Registration response code=$responseCode body=$responseText"
                )

                if (responseCode !in 200..299) {
                    throw Exception("HTTP $responseCode")
                }

                val json = JSONObject(responseText)
                val ok = json.optBoolean("ok", false)
                val registrationState = json.optString(
                    "registrationStatus",
                    json.optString("status", "")
                )

                runOnUiThread {
                    registerButton.isEnabled = true

                    if (ok) {
                        when (registrationState) {
                            "registered" -> {
                                registrationStatus.text =
                                    "●  Registration: REGISTERED"
                                registrationStatus.setTextColor(
                                    Color.rgb(36, 211, 124)
                                )
                                registerButton.text = "✓  Sudah Registered"
                                registerButton.isEnabled = false
                            }

                            "pending" -> {
                                registrationStatus.text =
                                    "●  Registration: MENUNGGU APPROVAL"
                                registrationStatus.setTextColor(
                                    Color.rgb(255, 190, 70)
                                )
                                registerButton.text = "⌁  Request Terkirim"
                            }

                            else -> {
                                registrationStatus.text =
                                    "●  Registration: REQUEST TERKIRIM"
                                registrationStatus.setTextColor(
                                    Color.rgb(255, 190, 70)
                                )
                                registerButton.text = "⌁  Request Terkirim"
                            }
                        }
                    } else {
                        registerButton.text = "⌁  Request Register"
                        registrationStatus.text =
                            "Registration: request gagal"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    TAG,
                    "Gagal Request Register",
                    e
                )
                runOnUiThread {
                    registerButton.isEnabled = true
                    registerButton.text = "⌁  Request Register"
                    registrationStatus.text =
                        "Registration: gagal request"
                }
            }
        }.start()
    }

    // =========================================================
    // BRIGHTNESS / DIM SCREEN
    // =========================================================

    private fun canWriteSettings(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.System.canWrite(this)
    }

    private fun isDimmed(): Boolean {
        return prefs.getBoolean(DIMMED_KEY, false)
    }

    private fun updateBrightnessUi() {
        if (!canWriteSettings()) {
            brightnessButton.text = "☀  Perizinan Layar"
            brightnessButton.isEnabled = true
            return
        }

        brightnessButton.isEnabled = true

        if (isDimmed()) {
            brightnessButton.text = "◐  Layar Sedang Redup"
        } else {
            brightnessButton.text = "☀  Redupkan Sekarang"
        }
    }

    private fun handleBrightnessClick() {
        if (!canWriteSettings()) {
            openWriteSettingsPermission()
            return
        }

        if (isDimmed()) {
            restoreScreenBrightness()
        } else {
            dimScreen()
        }
    }

    private fun dimScreen() {
        try {
            val resolver = contentResolver

            val currentMode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            val currentBrightness = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )

            if (!isDimmed()) {
                prefs.edit()
                    .putInt(PREVIOUS_BRIGHTNESS_KEY, currentBrightness)
                    .putInt(PREVIOUS_BRIGHTNESS_MODE_KEY, currentMode)
                    .apply()
            }

            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                DIM_BRIGHTNESS
            )

            prefs.edit()
                .putBoolean(DIMMED_KEY, true)
                .apply()

            updateBrightnessUi()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Gagal meredupkan layar", e)
        }
    }

    private fun restoreScreenBrightnessIfDimmed() {
        if (isDimmed()) {
            restoreScreenBrightness()
        }
    }

    private fun restoreScreenBrightness() {
        try {
            val resolver = contentResolver
            val previousBrightness = prefs.getInt(
                PREVIOUS_BRIGHTNESS_KEY,
                128
            )
            val previousMode = prefs.getInt(
                PREVIOUS_BRIGHTNESS_MODE_KEY,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                previousBrightness.coerceIn(1, 255)
            )

            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                previousMode
            )

            prefs.edit()
                .putBoolean(DIMMED_KEY, false)
                .apply()

            updateBrightnessUi()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Gagal mengembalikan brightness", e)
        }
    }

    private fun openWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS
            ).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // =========================================================
    // REQUEST SCREEN CAPTURE
    // =========================================================

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(
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

    @Deprecated("Deprecated in Android API, kept for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != CAPTURE_REQUEST_CODE) return

        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture: IZIN DITOLAK"
            android.util.Log.e(
                TAG,
                "Izin screen capture ditolak"
            )
            return
        }

        val serviceIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(
                ScreenCaptureService.EXTRA_RESULT_CODE,
                resultCode
            )
            putExtra(
                ScreenCaptureService.EXTRA_RESULT_DATA,
                data
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        status.text = "Screen capture: MEMULAI..."
    }

    private fun stopScreenCapture() {
        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(stopIntent)
        } else {
            startService(stopIntent)
        }

        status.text = "Screen capture: MENGHENTIKAN..."
    }

    // =========================================================
    // TOUCH RESTORE
    // =========================================================

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (
            ev.actionMasked == MotionEvent.ACTION_DOWN &&
            isDimmed()
        ) {
            // This works for touches received while this Activity is in
            // the foreground. Android does not expose global raw touch
            // events to a normal app while another app is foreground.
            restoreScreenBrightness()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        activeInstance = WeakReference(this)
        updateBrightnessUi()
        updateCaptureStatus()
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) {
            activeInstance = null
        }

        handler.removeCallbacks(uiPoll)
        handler.removeCallbacks(registrationPoll)

        try {
            webSocket.disconnect()
        } catch (e: Exception) {
            android.util.Log.e(
                TAG,
                "Gagal disconnect WebSocket",
                e
            )
        }

        // ScreenCaptureService owns MediaProjection and RealtimeManager.
        super.onDestroy()
    }
}
