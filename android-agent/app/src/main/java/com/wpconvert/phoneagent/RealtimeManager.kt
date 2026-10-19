
            PeerConnectionFactory.initialize(
                PeerConnectionFactory
                    .InitializationOptions
                    .builder(
                        context.applicationContext
                    )
                    .setEnableInternalTracer(
                        false
                    )
                    .createInitializationOptions()
            )

            val eglBase =
                org.webrtc.EglBase.create()

            val encoderFactory =
                org.webrtc.DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )

            val decoderFactory =
                org.webrtc.DefaultVideoDecoderFactory(
                    eglBase.eglBaseContext
                )

            peerConnectionFactory =
                PeerConnectionFactory
                    .builder()
                    .setVideoEncoderFactory(
                        encoderFactory
                    )
                    .setVideoDecoderFactory(
                        decoderFactory
                    )
                    .createPeerConnectionFactory()

            initialized =
                peerConnectionFactory != null

            if (initialized) {

                Log.d(
                    TAG,
                    "WebRTC berhasil diinisialisasi"
                )

            } else {

                Log.e(
                    TAG,
                    "PeerConnectionFactory = null"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal inisialisasi WebRTC",
                e
            )

            initialized = false
        }
    }

    // =====================================================
    // START SCREEN CAPTURE
    // =====================================================

    /*
     * Fungsi ini dipanggil oleh
     * ScreenCaptureService setelah
     * foreground service MediaProjection
     * sudah aktif.
     */

    fun startScreenCapture(
        resultCode: Int,
        projectionData: Intent
    ) {

        if (!initialized) {

            initialize()
        }

        if (capturing) {

            Log.d(
                TAG,
                "Screen capture sudah aktif"
            )

            return
        }

        val factory =
            peerConnectionFactory

        if (factory == null) {

            Log.e(
                TAG,
                "PeerConnectionFactory belum tersedia"
            )

            return
        }

        try {

            Log.d(
                TAG,
                "Menyiapkan WebRTC screen capture"
            )

            // =================================================
            // EGL
            // =================================================

            val eglBase =
                org.webrtc.EglBase.create()

            // =================================================
            // SURFACE TEXTURE HELPER
            // =================================================

            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    "ScreenCaptureThread",
                    eglBase.eglBaseContext
                )

            // =================================================
            // VIDEO SOURCE
            // =================================================

            videoSource =
                factory.createVideoSource(
                    false
                )

            // =================================================
            // SCREEN CAPTURER
            // =================================================

            screenCapturer =
                ScreenCapturerAndroid(
                    projectionData,
                    object : MediaProjection.Callback() {

                        override fun onStop() {

                            Log.d(
                                TAG,
                                "MediaProjection dihentikan Android"
                            )

                            capturing = false

                            /*
                             * Ini berbeda dari network/WebRTC disconnect.
                             * Android benar-benar mencabut MediaProjection.
                             *
                             * Auto reconnect PeerConnection sengaja TIDAK
                             * dijalankan di sini karena token projection lama
                             * sudah tidak valid. ScreenCaptureService harus
                             * menyediakan projection baru untuk kasus ini.
                             */
                            Log.w(
                                TAG,
                                "VIDEO: MediaProjection STOP -> menunggu service menyediakan projection baru"
                            )
                        }
                    }
                )

            val capturer =
                screenCapturer

            val helper =
                surfaceTextureHelper

            val source =
                videoSource

            if (
                capturer == null ||
