
        } catch (_: Exception) {
        }

        videoTrack =
            null

        try {

            videoSource?.dispose()

        } catch (_: Exception) {
        }

        videoSource =
            null

        try {

            surfaceTextureHelper?.dispose()

        } catch (_: Exception) {
        }

        surfaceTextureHelper =
            null

        capturing =
            false

        Log.d(
            TAG,
            "Screen capture dihentikan"
        )
    }

    // =====================================================
    // DISPOSE
    // =====================================================

    fun dispose() {

        videoReconnectHandler.removeCallbacksAndMessages(
            null
        )

        videoReconnectInProgress = false
        videoReconnectAttempts = 0
        videoReconnectGeneration++

        stopOutboundRtpStatsLogging()

        Log.d(
            TAG,
            "Dispose RealtimeManager"
        )

        stopScreenCapture()

        try {

            peerConnection?.close()

        } catch (_: Exception) {
        }

        peerConnection =
            null

        try {

            peerConnectionFactory?.dispose()

        } catch (_: Exception) {
        }

        peerConnectionFactory =
            null

        initialized =
            false

        controlHandler.removeCallbacksAndMessages(
            null
        )

        try {
            controlDataChannel?.unregisterObserver()
        } catch (_: Exception) {
        }

        try {
            controlDataChannel?.dispose()
        } catch (_: Exception) {
        }

        controlDataChannel =
            null
        controlChannelId =
            null
        controlChannelReady =
            false
        controlSetupStarted =
            false
        controlTransportReady =
            false
        controlTransportInProgress =
            false
        controlApplicationInProgress =
            false

        currentSessionId =
            null

        currentDeviceId =
            null

        Log.d(
            TAG,
            "RealtimeManager selesai dispose"
        )
    }
}
