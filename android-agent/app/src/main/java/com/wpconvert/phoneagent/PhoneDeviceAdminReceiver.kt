package com.wpconvert.phoneagent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PhoneDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "PhoneDeviceAdmin"
    }

    override fun onEnabled(
        context: Context,
        intent: Intent
    ) {
        super.onEnabled(context, intent)

        Log.d(
            TAG,
            "Device Admin enabled"
        )
    }

    override fun onDisabled(
        context: Context,
        intent: Intent
    ) {
        super.onDisabled(context, intent)

        Log.d(
            TAG,
            "Device Admin disabled"
        )
    }
}
