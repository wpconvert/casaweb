package com.wpconvert.phoneagent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val captureRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Web Phone Agent"
            textSize = 24f
        }

        status = TextView(this).apply {
            text = "Status: Menunggu izin screen capture"
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }

        val button = Button(this).apply {
            text = "Izinkan akses layar"
            setOnClickListener { requestScreenCapturePermission() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(button)
        setContentView(root)
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Deprecated in Android API, kept for simple prototype flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return

        status.text = if (resultCode == RESULT_OK && data != null) {
            "Status: Izin screen capture diberikan"
        } else {
            "Status: Izin screen capture ditolak"
        }
    }
}
