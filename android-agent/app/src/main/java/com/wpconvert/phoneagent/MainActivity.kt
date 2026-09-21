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

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var brightnessButton: Button

    // WebSocket
    private lateinit var webSocket: WebSocketClientManager

    private val captureRequestCode = 1001

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences(
            ScreenCaptureService.PREFS_NAME,
            MODE_PRIVATE
        )
    }


    private val statusPoll = object : Runnable {

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


            if (active) {

                status.text =
                    "Status: Screen capture aktif\n${width} × ${height}"

                captureButton.text =
                    "Hentikan screen capture"

            } else {

                status.text =
                    "Status: Menunggu izin screen capture"

                captureButton.text =
                    "Izinkan akses layar"
            }


            handler.postDelayed(this, 1000)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // =========================
        // WEBSOCKET SERVER
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


        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )


        val root = LinearLayout(this).apply {

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

                        val stopIntent =
                            Intent(
                                this@MainActivity,
                                ScreenCaptureService::class.java
                            ).apply {

                                action =
                                    ScreenCaptureService.ACTION_STOP
                            }


                        startService(stopIntent)

                    } else {

                        requestScreenCapturePermission()

                    }

                }
            }


        root.addView(title)

        root.addView(status)

        root.addView(brightnessButton)

        root.addView(captureButton)


        setContentView(root)


        handler.post(statusPoll)
    }


    private fun openWriteSettingsPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

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
        ) return


        if (
            resultCode != RESULT_OK ||
            data == null
        ) {

            status.text =
                "Status: Izin screen capture ditolak"

            return
        }


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

            startForegroundService(serviceIntent)

        } else {

            startService(serviceIntent)

        }


        status.text =
            "Status: Memulai screen capture..."
    }


    override fun onDestroy() {

        handler.removeCallbacks(
            statusPoll
        )

        // Tutup koneksi WebSocket
        webSocket.disconnect()

        super.onDestroy()
    }
}
