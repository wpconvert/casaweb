package com.wpconvert.phoneagent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.net.URL
import kotlin.concurrent.thread

class RealtimeManager(
    private val context: Context
) {

    companion object {

        private const val TAG =
            "RealtimeManager"

        private const val WORKER_URL =
            "https://web-phone-oneforall.danip4848.workers.dev"

        private const val CONTROL_CHANNEL_NAME =
            "controls"
    }

    // =====================================================
    // WEBRTC
    // =====================================================

    private var peerConnectionFactory:
        PeerConnectionFactory? = null

    private var peerConnection:
        PeerConnection? = null

    private var videoSource:
        VideoSource? = null

    private var videoTrack:
        VideoTrack? = null

    private var surfaceTextureHelper:
        SurfaceTextureHelper? = null

    private var screenCapturer:
        ScreenCapturerAndroid? = null

    // =====================================================
    // STATE
    // =====================================================

    private var initialized =
        false

    private var capturing =
        false

    private var currentSessionId:
        String? = null

    private var currentDeviceId:
        String? = null

    // =====================================================
    // CONTROL DATACHANNEL
    // =====================================================

    private var controlDataChannel:
        DataChannel? = null

    private var controlChannelId:
        Int? = null

    @Volatile
    private var controlChannelReady = false

    @Volatile
    private var controlSetupStarted = false

    @Volatile
    private var controlTransportReady = false

    @Volatile
    private var controlTransportInProgress = false

    @Volatile
    private var controlApplicationInProgress = false

    // =====================================================
    // VIDEO AUTO-RECONNECT
    // =====================================================

    @Volatile
    private var videoReconnectInProgress = false

    @Volatile
    private var videoReconnectGeneration = 0L

    private var videoReconnectAttempts = 0

    private val videoReconnectHandler =
        Handler(Looper.getMainLooper())

    private val videoReconnectRunnable =
        object : Runnable {
            override fun run() {
                reconnectVideoIfNeeded()
            }
        }

    private val controlHandler =
        Handler(Looper.getMainLooper())

    // =====================================================
    // OUTBOUND RTP DIAGNOSTICS
    // =====================================================

    private var outboundStatsLogging =
        false

    private val outboundStatsHandler =
        Handler(Looper.getMainLooper())

    private val outboundStatsRunnable =
        object : Runnable {

            override fun run() {

                logOutboundRtpStats()

                if (outboundStatsLogging) {
                    outboundStatsHandler.postDelayed(
                        this,
                        3000L
                    )
                }
            }
        }

    // =====================================================
    // INITIALIZE WEBRTC
    // =====================================================

    fun initialize() {

        if (initialized) {

            Log.d(
                TAG,
                "WebRTC sudah diinisialisasi"
            )

            return
        }

        // =====================================================
        // BUILD MARKER
        // =====================================================
        // Jika marker ini muncul di logcat, APK benar-benar
        // memakai source RealtimeManager.kt versi terbaru.
        Log.d(
            TAG,
            "BUILD MARKER: REMOTEPHONE CONTROL FIX 2026-09-25-D"
        )

        Log.d(
            TAG,
            "Memulai inisialisasi WebRTC"
        )

        try {

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
                helper == null ||
                source == null
            ) {

                Log.e(
                    TAG,
                    "Komponen screen capture tidak lengkap"
                )

                stopScreenCapture()

                return
            }

            // =================================================
            // CAPTURE OBSERVER
            // =================================================
            // Keep the original WebRTC observer, but also log every
            // incoming frame so we can verify that MediaProjection
            // is continuously producing frames.

            val frameObserver =
                object : org.webrtc.CapturerObserver {

                    private var frameCount = 0L

                    override fun onCapturerStarted(
                        success: Boolean
                    ) {
                        Log.d(
                            TAG,
                            "Capturer started: $success"
                        )

                        source.capturerObserver
                            .onCapturerStarted(success)
                    }

                    override fun onCapturerStopped() {
                        Log.d(
                            TAG,
                            "Capturer stopped"
                        )

                        source.capturerObserver
                            .onCapturerStopped()
                    }

                    override fun onFrameCaptured(
                        frame: org.webrtc.VideoFrame
                    ) {
                        frameCount++

                        if (frameCount == 1L || frameCount % 30L == 0L) {
                            Log.d(
                                TAG,
                                "CAPTURE FRAME #$frameCount ${frame.buffer.width}x${frame.buffer.height} rotation=${frame.rotation}"
                            )
                        }

                        // Forward EVERY frame to VideoSource.
                        source.capturerObserver
                            .onFrameCaptured(frame)
                    }
                }

            // =================================================
            // INITIALIZE CAPTURER
            // =================================================

            capturer.initialize(
                helper,
                context.applicationContext,
                frameObserver
            )

            // Explicitly request the same output format that the
            // Cloudflare publisher uses.
            // Keep native 720x1600 @ 30 FPS. Do not downscale here:
            // the browser should receive the real phone resolution and WebRTC
            // can adapt bitrate without us introducing an extra software resize.
            source.adaptOutputFormat(
                720,
                1600,
                30
            )

            // =================================================
            // START CAPTURE
            // =================================================

            Log.d(
                TAG,
                "Memulai capture 720x1600 @ 30 FPS"
            )

            capturer.startCapture(
                720,
                1600,
                30
            )

            // =================================================
            // VIDEO TRACK
            // =================================================

            videoTrack =
                factory.createVideoTrack(
                    "screen-track",
                    source
                )

            videoTrack?.setEnabled(
                true
            )

            capturing = true

            Log.d(
                TAG,
                "Screen capture berhasil dimulai"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SecurityException saat memulai MediaProjection",
                e
            )

            stopScreenCapture()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal memulai screen capture",
                e
            )

            stopScreenCapture()
        }
    }

    // =====================================================
    // PEER CONNECTION
    // =====================================================

    fun createPeerConnection() {

        if (!initialized) {

            initialize()
        }

        if (peerConnection != null) {

            Log.d(
                TAG,
                "PeerConnection sudah ada"
            )

            return
        }

        val factory =
            peerConnectionFactory

        if (factory == null) {

            Log.e(
                TAG,
                "PeerConnectionFactory tidak tersedia"
            )

            return
        }

        try {

            // =================================================
            // STUN
            // =================================================

            val iceServers =
                listOf(
                    PeerConnection.IceServer
                        .builder(
                            "stun:stun.cloudflare.com:3478"
                        )
                        .createIceServer()
                )

            val configuration =
                PeerConnection.RTCConfiguration(
                    iceServers
                )

            configuration.sdpSemantics =
                PeerConnection.SdpSemantics
                    .UNIFIED_PLAN

            // =================================================
            // PEER CONNECTION
            // =================================================

            peerConnection =
                factory.createPeerConnection(
                    configuration,
                    object :
                        PeerConnection.Observer {

                        override fun onSignalingChange(
                            state:
                                PeerConnection.SignalingState
                        ) {

                            Log.d(
                                TAG,
                                "Signaling state: $state"
                            )
                        }

                        override fun onIceConnectionChange(
                            state:
                                PeerConnection.IceConnectionState
                        ) {

                            Log.d(
                                TAG,
                                "ICE connection state: $state"
                            )

                            /*
                             * ICE bisa gagal sebelum PeerConnectionState
                             * berubah. Trigger reconnect dari sini juga.
                             *
                             * DISCONNECTED diberi jeda oleh
                             * scheduleVideoReconnect(), sehingga WebRTC
                             * masih punya kesempatan pulih sendiri.
                             */
                            when (state) {

                                PeerConnection.IceConnectionState.FAILED -> {
                                    scheduleVideoReconnect(
                                        PeerConnection.PeerConnectionState.FAILED
                                    )
                                }

                                PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    scheduleVideoReconnect(
                                        PeerConnection.PeerConnectionState.DISCONNECTED
                                    )
                                }

                                PeerConnection.IceConnectionState.CONNECTED,
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    // Koneksi sehat.
                                }

                                else -> {
                                    // NEW / CHECKING / CLOSED
                                }
                            }
                        }

                        override fun onIceConnectionReceivingChange(
                            receiving: Boolean
                        ) {

                            Log.d(
                                TAG,
                                "ICE receiving: $receiving"
                            )
                        }

                        override fun onIceGatheringChange(
                            state:
                                PeerConnection.IceGatheringState
                        ) {

                            Log.d(
                                TAG,
                                "ICE gathering state: $state"
                            )
                        }

                        override fun onIceCandidate(
                            candidate:
                                IceCandidate
                        ) {

                            Log.d(
                                TAG,
                                "ICE candidate received"
                            )
                        }

                        override fun onIceCandidatesRemoved(
                            candidates:
                                Array<out IceCandidate>
                        ) {

                            Log.d(
                                TAG,
                                "ICE candidates removed"
                            )
                        }

                        override fun onAddStream(
                            stream:
                                org.webrtc.MediaStream
                        ) {

                            Log.d(
                                TAG,
                                "Remote stream ditambahkan"
                            )
                        }

                        override fun onRemoveStream(
                            stream:
                                org.webrtc.MediaStream
                        ) {

                            Log.d(
                                TAG,
                                "Remote stream dihapus"
                            )
                        }

                        override fun onDataChannel(
                            dataChannel:
                                DataChannel
                        ) {

                            Log.d(
                                TAG,
                                "DataChannel diterima: label=${dataChannel.label()} id=${dataChannel.id()}"
                            )

                            if (
                                dataChannel.label() ==
                                CONTROL_CHANNEL_NAME
                            ) {
                                attachControlDataChannel(
                                    dataChannel
                                )
                            }
                        }

                        override fun onRenegotiationNeeded() {

                            Log.d(
                                TAG,
                                "Renegotiation diperlukan"
                            )
                        }

                        override fun onAddTrack(
                            receiver:
                                org.webrtc.RtpReceiver,
                            mediaStreams:
                                Array<out org.webrtc.MediaStream>
                        ) {

                            Log.d(
                                TAG,
                                "Track diterima"
                            )
                        }

                        override fun onTrack(
                            transceiver:
                                org.webrtc.RtpTransceiver
                        ) {

                            Log.d(
                                TAG,
                                "Transceiver track diterima"
                            )
                        }

                        override fun onIceCandidateError(
                            event:
                                org.webrtc.IceCandidateErrorEvent
                        ) {

                            Log.e(
                                TAG,
                                "ICE candidate error: " +
                                    event.errorText
                            )
                        }

                        override fun onSelectedCandidatePairChanged(
                            event:
                                org.webrtc.CandidatePairChangeEvent
                        ) {

                            Log.d(
                                TAG,
                                "Selected candidate pair berubah"
                            )
                        }

                        override fun onConnectionChange(
                            newState:
                                PeerConnection.PeerConnectionState
                        ) {

                            Log.d(
                                TAG,
                                "PeerConnection state: $newState"
                            )

                            if (
                                newState ==
                                    PeerConnection.PeerConnectionState.CONNECTED
                            ) {
                                videoReconnectAttempts = 0

                                Log.d(
                                    TAG,
                                    "BUILD MARKER: PEER CONNECTED 2026-09-25-VIDEO-RECONNECT"
                                )

                                if (controlChannelReady) {
                                    Log.d(
                                        TAG,
                                        "CONTROL: PeerConnection CONNECTED dan control sudah READY"
                                    )
                                }
                            }

                            if (
                                newState ==
                                    PeerConnection.PeerConnectionState.DISCONNECTED ||
                                newState ==
                                    PeerConnection.PeerConnectionState.FAILED ||
                                newState ==
                                    PeerConnection.PeerConnectionState.CLOSED
                            ) {
                                scheduleVideoReconnect(newState)
                            }
                        }
                    }
                )

            if (peerConnection == null) {

                Log.e(
                    TAG,
                    "Gagal membuat PeerConnection"
                )

                return
            }

            Log.d(
                TAG,
                "PeerConnection berhasil dibuat"
            )

            // =================================================
            // ADD SCREEN TRACK
            // =================================================

            videoTrack?.let { track ->

                peerConnection?.addTrack(
                    track,
                    listOf(
                        "screen-stream"
                    )
                )

                Log.d(
                    TAG,
                    "Screen video track ditambahkan"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membuat PeerConnection",
                e
            )
        }
    }

    // =====================================================
    // =====================================================
    // VIDEO AUTO-RECONNECT
    // =====================================================
    //
    // Auto reconnect hanya membangun ulang:
    //   PeerConnection + Cloudflare session + Control DataChannel
    //
    // MediaProjection, ScreenCapturerAndroid, VideoSource dan VideoTrack
    // TIDAK disentuh. Screen capture tetap hidup selama Android tidak
    // mencabut MediaProjection.
    //

    private fun scheduleVideoReconnect(
        state: PeerConnection.PeerConnectionState
    ) {
        if (!capturing) {
            Log.w(
                TAG,
                "VIDEO RECONNECT: capture tidak aktif, skip state=$state"
            )
            return
        }

        if (videoReconnectInProgress) {
            Log.d(
                TAG,
                "VIDEO RECONNECT: sudah berjalan, skip state=$state"
            )
            return
        }

        videoReconnectAttempts++

        val delayMs =
            when {
                state == PeerConnection.PeerConnectionState.DISCONNECTED &&
                    videoReconnectAttempts == 1 -> 1500L

                videoReconnectAttempts <= 1 -> 1000L
                videoReconnectAttempts == 2 -> 2000L
                videoReconnectAttempts == 3 -> 4000L
                videoReconnectAttempts == 4 -> 6000L
                else -> 10000L
            }

        Log.w(
            TAG,
            "VIDEO RECONNECT: state=$state " +
                "attempt=$videoReconnectAttempts " +
                "delay=${delayMs}ms " +
                "capture=$capturing"
        )

        videoReconnectHandler.removeCallbacks(
            videoReconnectRunnable
        )

        videoReconnectHandler.postDelayed(
            videoReconnectRunnable,
            delayMs
        )
    }

    private fun reconnectVideoIfNeeded() {
        if (!capturing) {
            Log.w(
                TAG,
                "VIDEO RECONNECT: capture sudah tidak aktif"
            )
            return
        }

        if (videoReconnectInProgress) {
            return
        }

        val oldConnection =
            peerConnection

        val state =
            oldConnection?.connectionState()

        if (
            state == PeerConnection.PeerConnectionState.CONNECTED
        ) {
            videoReconnectAttempts = 0

            Log.d(
                TAG,
                "VIDEO RECONNECT: koneksi sudah CONNECTED, tidak perlu rebuild"
            )

            return
        }

        val deviceId =
            currentDeviceId

        if (deviceId.isNullOrBlank()) {
            Log.e(
                TAG,
                "VIDEO RECONNECT: currentDeviceId kosong, tidak bisa publish ulang"
            )

            videoReconnectInProgress = false

            scheduleVideoReconnect(
                PeerConnection.PeerConnectionState.FAILED
            )

            return
        }

        videoReconnectInProgress = true

        val generation =
            ++videoReconnectGeneration

        Log.w(
            TAG,
            "VIDEO RECONNECT: mulai rebuild generation=$generation " +
                "state=$state " +
                "capture=$capturing"
        )

        controlHandler.post {

            try {

                // -------------------------------------------------
                // 1. Buang control DataChannel lama.
                // -------------------------------------------------
                try {
                    controlDataChannel?.unregisterObserver()
                } catch (_: Exception) {
                }

                try {
                    controlDataChannel?.dispose()
                } catch (_: Exception) {
                }

                controlDataChannel = null
                controlChannelReady = false
                controlChannelId = null
                controlSetupStarted = false
                controlTransportReady = false
                controlTransportInProgress = false
                controlApplicationInProgress = false

                // -------------------------------------------------
                // 2. Tutup PeerConnection lama.
                // -------------------------------------------------
                try {
                    oldConnection?.close()
                } catch (_: Exception) {
                }

                if (peerConnection === oldConnection) {
                    peerConnection = null
                }

                // -------------------------------------------------
                // 3. Buat PeerConnection baru.
                //
                // VideoTrack yang sedang dipakai tetap digunakan.
                // MediaProjection / ScreenCapturer TIDAK di-restart.
                // -------------------------------------------------
                createPeerConnection()

                if (peerConnection == null) {
                    throw IllegalStateException(
                        "PeerConnection baru gagal dibuat"
                    )
                }

                Log.d(
                    TAG,
                    "VIDEO RECONNECT: PeerConnection baru dibuat; VideoTrack lama tetap dipakai"
                )

                // -------------------------------------------------
                // 4. Session Cloudflare baru.
                //
                // Session publisher lama bisa sudah tidak valid setelah
                // PeerConnection putus. Kita buat session baru tanpa
                // menyentuh MediaProjection.
                // -------------------------------------------------
                Log.d(
                    TAG,
                    "VIDEO RECONNECT: meminta Cloudflare session baru"
                )

                createCloudflareSession {
                    sessionOk,
                    newSessionId,
                    sessionError ->

                    controlHandler.post {

                        if (
                            generation !=
                                videoReconnectGeneration
                        ) {
                            Log.w(
                                TAG,
                                "VIDEO RECONNECT: generation sudah berubah, abaikan session lama"
                            )

                            videoReconnectInProgress = false
                            return@post
                        }

                        if (
                            !sessionOk ||
                            newSessionId.isNullOrBlank()
                        ) {
                            videoReconnectInProgress = false

                            Log.e(
                                TAG,
                                "VIDEO RECONNECT: session baru gagal: " +
                                    (sessionError ?: "unknown error")
                            )

                            scheduleVideoReconnect(
                                PeerConnection.PeerConnectionState.FAILED
                            )

                            return@post
                        }

                        Log.d(
                            TAG,
                            "VIDEO RECONNECT: session baru=$newSessionId"
                        )

                        // -------------------------------------------------
                        // 5. Publish ulang video.
                        //
                        // publishToCloudflare() akan menjalankan kembali
                        // pipeline Control DataChannel setelah media CONNECTED.
                        // -------------------------------------------------
                        publishToCloudflare(
                            newSessionId,
                            deviceId
                        ) { success, _, error ->

                            controlHandler.post {

                                if (
                                    generation !=
                                        videoReconnectGeneration
                                ) {
                                    Log.w(
                                        TAG,
                                        "VIDEO RECONNECT: hasil publish berasal dari generation lama"
                                    )

                                    videoReconnectInProgress = false
                                    return@post
                                }

                                if (success) {

                                    videoReconnectAttempts = 0
                                    videoReconnectInProgress = false

                                    Log.d(
                                        TAG,
                                        "========================================"
                                    )

                                    Log.d(
                                        TAG,
                                        "VIDEO RECONNECT BERHASIL"
                                    )

                                    Log.d(
                                        TAG,
                                        "MediaProjection tetap hidup"
                                    )

                                    Log.d(
                                        TAG,
                                        "VideoTrack tetap dipakai"
                                    )

                                    Log.d(
                                        TAG,
                                        "PeerConnection baru aktif"
                                    )

                                    Log.d(
                                        TAG,
                                        "Control setup dibuat ulang"
                                    )

                                    Log.d(
                                        TAG,
                                        "========================================"
                                    )

                                } else {

                                    videoReconnectInProgress = false

                                    Log.e(
                                        TAG,
                                        "VIDEO RECONNECT: publish ulang gagal: " +
                                            (error ?: "unknown error")
                                    )

                                    scheduleVideoReconnect(
                                        PeerConnection.PeerConnectionState.FAILED
                                    )
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {

                videoReconnectInProgress = false

                Log.e(
                    TAG,
                    "VIDEO RECONNECT: exception saat rebuild",
                    e
                )

                scheduleVideoReconnect(
                    PeerConnection.PeerConnectionState.FAILED
                )
            }
        }
    }

    // =====================================================
    // CREATE OFFER
    // =====================================================

    fun createOffer(
        callback:
            (SessionDescription?) -> Unit
    ) {

        val connection =
            peerConnection

        if (connection == null) {

            Log.e(
                TAG,
                "PeerConnection belum dibuat"
            )

            callback(null)

            return
        }

        val constraints =
            MediaConstraints().apply {

                mandatory.add(
                    MediaConstraints.KeyValuePair(
                        "OfferToReceiveAudio",
                        "false"
                    )
                )

                mandatory.add(
                    MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo",
                        "false"
                    )
                )
            }

        connection.createOffer(
            object :
                org.webrtc.SdpObserver {

                override fun onCreateSuccess(
                    description:
                        SessionDescription
                ) {

                    Log.d(
                        TAG,
                        "SDP Offer berhasil dibuat"
                    )

                    connection.setLocalDescription(
                        object :
                            org.webrtc.SdpObserver {

                            override fun onCreateSuccess(
                                description:
                                    SessionDescription
                            ) {
                            }

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "Local SDP berhasil diset"
                                )

                                /*
                                 * PENTING:
                                 *
                                 * Jangan langsung mengirim SDP hasil
                                 * createOffer().
                                 *
                                 * Cloudflare Worker menerima SDP sebagai
                                 * satu paket, jadi ICE candidate harus sudah
                                 * terkumpul di localDescription terlebih dahulu.
                                 */
                                waitForIceGatheringComplete(
                                    connection
                                ) { finalDescription ->

                                    if (finalDescription == null) {

                                        Log.e(
                                            TAG,
                                            "Local SDP final tidak tersedia setelah ICE gathering"
                                        )

                                        callback(null)

                                        return@waitForIceGatheringComplete
                                    }

                                    Log.d(
                                        TAG,
                                        "ICE gathering selesai"
                                    )

                                    Log.d(
                                        TAG,
                                        "Mengirim SDP final yang sudah berisi ICE candidate"
                                    )

                                    callback(
                                        finalDescription
                                    )
                                }
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {

                                Log.e(
                                    TAG,
                                    "Local SDP create failure: $error"
                                )

                                callback(null)
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                Log.e(
                                    TAG,
                                    "Local SDP set failure: $error"
                                )

                                callback(null)
                            }
                        },
                        description
                    )
                }

                override fun onSetSuccess() {
                }

                override fun onCreateFailure(
                    error: String
                ) {

                    Log.e(
                        TAG,
                        "Offer create failure: $error"
                    )

                    callback(null)
                }

                override fun onSetFailure(
                    error: String
                ) {
                }
            },
            constraints
        )
    }

    // =====================================================
    // WAIT ICE GATHERING COMPLETE
    // =====================================================

    private fun waitForIceGatheringComplete(
        connection: PeerConnection,
        callback:
            (SessionDescription?) -> Unit
    ) {

        /*
         * Jika ICE sudah selesai sebelum fungsi ini dipanggil,
         * langsung ambil localDescription terbaru.
         */
        if (
            connection.iceGatheringState() ==
                PeerConnection.IceGatheringState.COMPLETE
        ) {

            Log.d(
                TAG,
                "ICE gathering sudah COMPLETE"
            )

            callback(
                connection.localDescription
            )

            return
        }

        val handler =
            Handler(
                Looper.getMainLooper()
            )

        val startTime =
            System.currentTimeMillis()

        val timeoutMs =
            10000L

        val checkRunnable =
            object : Runnable {

                override fun run() {

                    val state =
                        connection.iceGatheringState()

                    val localDescription =
                        connection.localDescription

                    if (
                        state ==
                            PeerConnection.IceGatheringState.COMPLETE
                    ) {

                        Log.d(
                            TAG,
                            "ICE gathering COMPLETE"
                        )

                        callback(
                            localDescription
                        )

                        return
                    }

                    val elapsed =
                        System.currentTimeMillis() -
                            startTime

                    if (
                        elapsed >= timeoutMs
                    ) {

                        Log.w(
                            TAG,
                            "ICE gathering timeout setelah ${timeoutMs}ms"
                        )

                        /*
                         * Tetap gunakan localDescription terbaru jika
                         * tersedia. Ini mencegah aplikasi menggantung
                         * selamanya jika ICE gathering tidak mencapai
                         * COMPLETE pada device/network tertentu.
                         */
                        callback(
                            localDescription
                        )

                        return
                    }

                    handler.postDelayed(
                        this,
                        50L
                    )
                }
            }

        handler.post(
            checkRunnable
        )
    }

    // =====================================================
    // CREATE CLOUDFLARE SESSION
    // =====================================================

    fun createCloudflareSession(
        callback:
            (success: Boolean,
             sessionId: String?,
             error: String?) -> Unit
    ) {

        Log.d(
            TAG,
            "Meminta Cloudflare session baru"
        )

        thread {

            try {

                val url =
                    URL(
                        "$WORKER_URL/api/session"
                    )

                val connectionHttp =
                    url.openConnection()
                        as HttpURLConnection

                connectionHttp.requestMethod =
                    "POST"

                connectionHttp.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                connectionHttp.connectTimeout =
                    15000

                connectionHttp.readTimeout =
                    30000

                connectionHttp.doOutput =
                    true

                connectionHttp.outputStream.use {
                    it.write(
                        "{}".toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }

                val responseCode =
                    connectionHttp.responseCode

                val responseText =
                    if (
                        responseCode in 200..299
                    ) {

                        connectionHttp
                            .inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    } else {

                        connectionHttp
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            ?: "HTTP $responseCode"
                    }

                Log.d(
                    TAG,
                    "Session response code: $responseCode"
                )

                Log.d(
                    TAG,
                    "Session response: $responseText"
                )

                if (
                    responseCode !in 200..299
                ) {

                    callback(
                        false,
                        null,
                        responseText
                    )

                    connectionHttp.disconnect()

                    return@thread
                }

                val json =
                    JSONObject(
                        responseText
                    )

                val sessionId =
                    json.optString(
                        "sessionId",
                        ""
                    )

                if (
                    sessionId.isEmpty()
                ) {

                    callback(
                        false,
                        null,
                        "Cloudflare tidak mengembalikan sessionId: $responseText"
                    )

                    connectionHttp.disconnect()

                    return@thread
                }

                currentSessionId =
                    sessionId

                Log.d(
                    TAG,
                    "Cloudflare session berhasil: $sessionId"
                )

                callback(
                    true,
                    sessionId,
                    null
                )

                connectionHttp.disconnect()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gagal membuat Cloudflare session",
                    e
                )

                callback(
                    false,
                    null,
                    e.message
                        ?: "Unknown error"
                )
            }
        }
    }

    // =====================================================
    // PUBLISH CLOUDFLARE
    // =====================================================

    fun publishToCloudflare(
        sessionId: String,
        deviceId: String,
        callback: (
            success: Boolean,
            answer: SessionDescription?,
            error: String?
        ) -> Unit
    ) {
        val connection = peerConnection

        if (connection == null) {
            callback(false, null, "PeerConnection belum dibuat")
            return
        }

        currentSessionId = sessionId
        currentDeviceId = deviceId

        controlSetupStarted = false
        controlTransportReady = false
        controlTransportInProgress = false
        controlApplicationInProgress = false
        controlChannelReady = false
        controlChannelId = null

        try { controlDataChannel?.unregisterObserver() } catch (_: Exception) { }
        try { controlDataChannel?.dispose() } catch (_: Exception) { }
        controlDataChannel = null

        Log.d(TAG, "BUILD MARKER: PUBLISH ORDER MEDIA-FIRST CONTROL-SECOND 2026-09-25-D")
        Log.d(TAG, "Publish pipeline: VIDEO -> CONNECTED -> DataChannel transport -> controls")

        // Cloudflare's current recipe for adding DataChannels to an existing
        // media connection starts after the media offer/answer is complete.
        // We therefore publish video first, then add the DataChannel transport,
        // then allocate the controls publication. Every mutation is serialized.
        publishVideoToCloudflareInternal(
            sessionId,
            deviceId
        ) { videoSuccess, answer, videoError ->

            if (!videoSuccess) {
                callback(false, answer, videoError)
                return@publishVideoToCloudflareInternal
            }

            // Preserve the existing working video behavior.
            callback(true, answer, null)

            // Start control setup only after the media answer has been applied.
            controlHandler.post {
                startControlAfterMediaConnected(sessionId)
            }
        }
    }

    private fun startControlAfterMediaConnected(
        sessionId: String
    ) {
        if (controlSetupStarted) {
            Log.d(TAG, "CONTROL: setup sudah berjalan, skip duplicate start")
            return
        }

        controlSetupStarted = true
        Log.d(TAG, "CONTROL: menunggu media PeerConnection CONNECTED")

        waitForPeerConnectionConnected(30000L) { connected ->
            if (!connected) {
                Log.e(TAG, "CONTROL: media PeerConnection tidak CONNECTED")
                controlSetupStarted = false
                return@waitForPeerConnectionConnected
            }

            Log.d(TAG, "BUILD MARKER: MEDIA CONNECTED -> CONTROL TRANSPORT 2026-09-25-D")

            ensureControlTransport(sessionId) { transportOk, transportError ->
                if (!transportOk) {
                    Log.e(TAG, "CONTROL: transport gagal: $transportError")
                    controlSetupStarted = false
                    return@ensureControlTransport
                }

                Log.d(TAG, "CONTROL: transport READY -> publish controls")

                createPublisherControlChannel(sessionId) { controlOk, controlError ->
                    if (!controlOk) {
                        Log.e(TAG, "CONTROL: publication controls gagal: $controlError")
                        controlSetupStarted = false
                        return@createPublisherControlChannel
                    }

                    waitForControlReady(15000L) { ready ->
                        if (ready) {
                            Log.d(TAG, "BUILD MARKER: CONTROL READY 2026-09-25-D")
                        } else {
                            Log.e(TAG, "CONTROL: publication controls ada tetapi native channel tidak OPEN")
                            controlSetupStarted = false
                        }
                    }
                }
            }
        }
    }

    private fun publishVideoToCloudflareInternal(
        sessionId: String,
        deviceId: String,
        callback: (
            success: Boolean,
            answer: SessionDescription?,
            error: String?
        ) -> Unit
    ) {
        val connection = peerConnection

        if (connection == null) {
            callback(false, null, "PeerConnection belum dibuat")
            return
        }

        currentSessionId = sessionId

        Log.d(TAG, "VIDEO PUBLISH: membuat offer media")

        createOffer { offer ->
            if (offer == null) {
                callback(false, null, "Gagal membuat SDP offer")
                return@createOffer
            }

            val sdp = offer.description

            thread {
                var connectionHttp: HttpURLConnection? = null

                try {
                    val url = URL("$WORKER_URL/api/publish")

                    connectionHttp =
                        url.openConnection() as HttpURLConnection

                    connectionHttp.requestMethod = "POST"

                    connectionHttp.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    connectionHttp.setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    connectionHttp.connectTimeout = 15000
                    connectionHttp.readTimeout = 30000
                    connectionHttp.doOutput = true

                    val body = JSONObject().apply {
                        put("sessionId", sessionId)
                        put("deviceId", deviceId)
                        put("sdp", sdp)
                        put("mid", "0")
                        put("trackName", "screen-$deviceId")
                    }

                    Log.d(TAG, "VIDEO PUBLISH: mengirim SDP ke Worker")
                    Log.d(TAG, "VIDEO PUBLISH: deviceId=$deviceId")
                    Log.d(TAG, "VIDEO PUBLISH: sessionId=$sessionId")

                    connectionHttp.outputStream.use {
                        it.write(
                            body.toString()
                                .toByteArray(Charsets.UTF_8)
                        )
                    }

                    val responseCode = connectionHttp.responseCode

                    val responseText =
                        if (responseCode in 200..299) {
                            connectionHttp.inputStream
                                .bufferedReader()
                                .use { it.readText() }
                        } else {
                            connectionHttp.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: "HTTP $responseCode"
                        }

                    Log.d(
                        TAG,
                        "VIDEO PUBLISH: Cloudflare response code=$responseCode"
                    )

                    Log.d(
                        TAG,
                        "VIDEO PUBLISH: Cloudflare response=$responseText"
                    )

                    if (responseCode !in 200..299) {
                        callback(false, null, responseText)
                        return@thread
                    }

                    val json = JSONObject(responseText)

                    val cloudflare =
                        json.optJSONObject("cloudflare")

                    if (cloudflare == null) {
                        callback(
                            false,
                            null,
                            "Response Worker tidak memiliki object cloudflare: $responseText"
                        )
                        return@thread
                    }

                    val sessionDescription =
                        cloudflare.optJSONObject("sessionDescription")

                    if (sessionDescription == null) {
                        callback(
                            false,
                            null,
                            "Cloudflare tidak mengembalikan sessionDescription: $responseText"
                        )
                        return@thread
                    }

                    val answerSdp =
                        sessionDescription.optString("sdp", "")

                    if (answerSdp.isEmpty()) {
                        callback(
                            false,
                            null,
                            "Cloudflare tidak mengembalikan SDP answer: $responseText"
                        )
                        return@thread
                    }

                    val answer =
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            answerSdp
                        )

                    connection.setRemoteDescription(
                        object : org.webrtc.SdpObserver {

                            override fun onCreateSuccess(
                                description: SessionDescription
                            ) {
                            }

                            override fun onSetSuccess() {
                                Log.d(
                                    TAG,
                                    "VIDEO PUBLISH: Remote SDP Cloudflare berhasil diset"
                                )

                                startOutboundRtpStatsLogging()

                                callback(
                                    true,
                                    answer,
                                    null
                                )
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {
                                Log.e(
                                    TAG,
                                    "VIDEO PUBLISH: onCreateFailure=$error"
                                )

                                callback(false, null, error)
                            }

                            override fun onSetFailure(
                                error: String
                            ) {
                                Log.e(
                                    TAG,
                                    "VIDEO PUBLISH: onSetFailure=$error"
                                )

                                callback(false, null, error)
                            }
                        },
                        answer
                    )
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "VIDEO PUBLISH: gagal publish ke Cloudflare",
                        e
                    )

                    callback(
                        false,
                        null,
                        e.message ?: "Unknown error"
                    )
                } finally {
                    connectionHttp?.disconnect()
                }
            }
        }
    }

    // =====================================================
    // CONTROL DATACHANNEL
    // =====================================================

    /**
     * Control setup yang baru:
     *
     * 1. Pastikan DataChannel transport Cloudflare sudah dibuat.
     * 2. Selesaikan SFU offer/answer dan tunggu PeerConnection CONNECTED.
     * 3. Buat publication local "controls" di publisher session.
     * 4. Buat negotiated DataChannel menggunakan ID yang diberikan SFU.
     *
     * Tidak ada mutation kedua sebelum mutation/SDP sebelumnya selesai.
     */
    private fun ensureControlReadyBeforeVideoPublish(
        sessionId: String,
        callback: () -> Unit
    ) {
        if (controlChannelReady &&
            controlDataChannel?.state() == DataChannel.State.OPEN
        ) {
            Log.d(TAG, "CONTROL: channel sudah OPEN")
            callback()
            return
        }

        if (controlApplicationInProgress || controlTransportInProgress) {
            Log.d(TAG, "CONTROL: setup sedang berjalan, menunggu")

            waitForControlReady(30000L) {
                if (it) {
                    callback()
                } else {
                    Log.e(TAG, "CONTROL: timeout menunggu control")
                    callback()
                }
            }

            return
        }

        controlSetupStarted = true

        ensureControlTransport(sessionId) { transportOk, transportError ->
            if (!transportOk) {
                Log.e(
                    TAG,
                    "CONTROL: transport gagal: $transportError"
                )

                /*
                 * Jangan membuat video mati hanya karena control gagal.
                 * Video tetap boleh berjalan; control bisa diretry kemudian.
                 */
                callback()
                return@ensureControlTransport
            }

            createPublisherControlChannel(
                sessionId
            ) { controlOk, controlError ->

                if (!controlOk) {
                    Log.e(
                        TAG,
                        "CONTROL: publication controls gagal: $controlError"
                    )

                    controlSetupStarted = false
                    callback()
                    return@createPublisherControlChannel
                }

                waitForControlReady(15000L) { ready ->
                    if (ready) {
                        Log.d(
                            TAG,
                            "BUILD MARKER: CONTROL READY 2026-09-25-C"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "CONTROL: publication dibuat tetapi channel belum OPEN"
                        )
                    }

                    callback()
                }
            }
        }
    }

    /**
     * DataChannel transport hanya dibuat satu kali untuk session ini.
     *
     * Endpoint Worker tetap menggunakan bentuk request yang sekarang:
     * sessionId + location + dataChannelName.
     */
    private fun ensureControlTransport(
        sessionId: String,
        callback: (Boolean, String?) -> Unit
    ) {
        if (controlTransportReady) {
            Log.d(TAG, "CONTROL: transport sudah READY")
            callback(true, null)
            return
        }

        if (controlTransportInProgress) {
            Log.d(TAG, "CONTROL: transport sedang diproses")

            waitForControlTransport(30000L) { ok ->
                if (ok) {
                    callback(true, null)
                } else {
                    callback(
                        false,
                        "Timeout menunggu DataChannel transport"
                    )
                }
            }

            return
        }

        val connection = peerConnection

        if (connection == null) {
            callback(false, "PeerConnection belum tersedia")
            return
        }

        controlTransportInProgress = true

        Log.d(
            TAG,
            "BUILD MARKER: CONTROL TRANSPORT START 2026-09-25-C"
        )

        Log.d(
            TAG,
            "CONTROL: datachannel-establish session=$sessionId"
        )

        postJson(
            "/api/datachannel-establish",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("location", "remote")
                put("dataChannelName", "server-events")
            }
        ) { ok, establishJson, error ->

            if (!ok || establishJson == null) {
                controlTransportInProgress = false

                callback(
                    false,
                    error ?: "DataChannel establish gagal"
                )

                return@postJson
            }

            val cloudflare =
                establishJson.optJSONObject("cloudflare")

            val description =
                establishJson.optJSONObject("sessionDescription")
                    ?: cloudflare?.optJSONObject("sessionDescription")

            if (description == null) {
                controlTransportInProgress = false

                callback(
                    false,
                    "sessionDescription DataChannel tidak ditemukan"
                )

                return@postJson
            }

            val offerSdp =
                description.optString("sdp", "")

            if (offerSdp.isBlank()) {
                controlTransportInProgress = false

                callback(
                    false,
                    "SDP DataChannel kosong"
                )

                return@postJson
            }

            val offer =
                SessionDescription(
                    SessionDescription.Type.OFFER,
                    offerSdp
                )

            /*
             * Serialize SDP operation on the PeerConnection.
             * Jangan membuat offer lain sampai renegotiate selesai.
             */
            controlHandler.post {

                connection.setRemoteDescription(
                    object : org.webrtc.SdpObserver {

                        override fun onCreateSuccess(
                            description: SessionDescription
                        ) {
                        }

                        override fun onSetSuccess() {

                            Log.d(
                                TAG,
                                "CONTROL: SFU transport offer berhasil diset"
                            )

                            connection.createAnswer(
                                object : org.webrtc.SdpObserver {

                                    override fun onCreateSuccess(
                                        answer: SessionDescription
                                    ) {
                                        connection.setLocalDescription(
                                            object :
                                                org.webrtc.SdpObserver {

                                                override fun onCreateSuccess(
                                                    description:
                                                        SessionDescription
                                                ) {
                                                }

                                                override fun onSetSuccess() {

                                                    waitForIceGathering {
                                                        val localSdp =
                                                            connection
                                                                .localDescription
                                                                ?.description
                                                                ?: ""

                                                        if (localSdp.isBlank()) {
                                                            controlTransportInProgress =
                                                                false

                                                            callback(
                                                                false,
                                                                "Local SDP DataChannel kosong"
                                                            )

                                                            return@waitForIceGathering
                                                        }

                                                        Log.d(
                                                            TAG,
                                                            "CONTROL: mengirim answer transport ke /api/renegotiate"
                                                        )

                                                        putJson(
                                                            "/api/renegotiate",
                                                            JSONObject().apply {
                                                                put(
                                                                    "sessionId",
                                                                    sessionId
                                                                )

                                                                put(
                                                                    "sdp",
                                                                    localSdp
                                                                )
                                                            }
                                                        ) {
                                                                renegotiateOk,
                                                                _,
                                                                renegotiateError ->

                                                            if (!renegotiateOk) {
                                                                controlTransportInProgress =
                                                                    false

                                                                callback(
                                                                    false,
                                                                    renegotiateError
                                                                        ?: "Renegotiate DataChannel gagal"
                                                                )

                                                                return@putJson
                                                            }

                                                            Log.d(
                                                                TAG,
                                                                "CONTROL: transport renegotiate diterima Cloudflare"
                                                            )

                                                            waitForPeerConnectionConnected(
                                                                20000L
                                                            ) {
                                                                connected ->

                                                                if (!connected) {
                                                                    controlTransportInProgress =
                                                                        false

                                                                    callback(
                                                                        false,
                                                                        "PeerConnection tidak CONNECTED setelah DataChannel transport"
                                                                    )

                                                                    return@waitForPeerConnectionConnected
                                                                }

                                                                controlTransportReady =
                                                                    true

                                                                controlTransportInProgress =
                                                                    false

                                                                Log.d(
                                                                    TAG,
                                                                    "BUILD MARKER: CONTROL TRANSPORT READY 2026-09-25-C"
                                                                )

                                                                callback(
                                                                    true,
                                                                    null
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                override fun onCreateFailure(
                                                    error: String
                                                ) {
                                                    controlTransportInProgress =
                                                        false

                                                    callback(
                                                        false,
                                                        error
                                                    )
                                                }

                                                override fun onSetFailure(
                                                    error: String
                                                ) {
                                                    controlTransportInProgress =
                                                        false

                                                    callback(
                                                        false,
                                                        error
                                                    )
                                                }
                                            },
                                            answer
                                        )
                                    }

                                    override fun onSetSuccess() {
                                    }

                                    override fun onCreateFailure(
                                        error: String
                                    ) {
                                        controlTransportInProgress =
                                            false

                                        callback(
                                            false,
                                            error
                                        )
                                    }

                                    override fun onSetFailure(
                                        error: String
                                    ) {
                                        controlTransportInProgress =
                                            false

                                        callback(
                                            false,
                                            error
                                        )
                                    }
                                },
                                MediaConstraints()
                            )
                        }

                        override fun onCreateFailure(
                            error: String
                        ) {
                            controlTransportInProgress = false

                            callback(
                                false,
                                error
                            )
                        }

                        override fun onSetFailure(
                            error: String
                        ) {
                            controlTransportInProgress = false

                            callback(
                                false,
                                error
                            )
                        }
                    },
                    offer
                )
            }
        }
    }

    private fun createPublisherControlChannel(
        sessionId: String,
        callback: ((Boolean, String?) -> Unit)?
    ) {
        if (!controlTransportReady) {
            callback?.invoke(
                false,
                "DataChannel transport belum READY"
            )
            return
        }

        if (controlApplicationInProgress) {
            callback?.invoke(
                false,
                "Control application channel sedang dibuat"
            )
            return
        }

        if (controlChannelReady &&
            controlDataChannel?.state() == DataChannel.State.OPEN
        ) {
            callback?.invoke(true, null)
            return
        }

        controlApplicationInProgress = true

        Log.d(
            TAG,
            "CONTROL: datachannel-publish controls session=$sessionId"
        )

        /*
         * Ini adalah publication LOCAL di publisher session.
         *
         * Cloudflare docs:
         * datachannels/new
         * location = local
         * dataChannelName = controls
         */
        postJson(
            "/api/datachannel-publish",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("dataChannelName", CONTROL_CHANNEL_NAME)
                put("ordered", true)
            }
        ) { ok, json, error ->

            if (!ok || json == null) {
                controlApplicationInProgress = false

                callback?.invoke(
                    false,
                    error ?: "DataChannel publish gagal"
                )

                return@postJson
            }

            Log.d(
                TAG,
                "CONTROL: datachannel-publish response=$json"
            )

            val channels =
                json.optJSONArray("dataChannels")
                    ?: json.optJSONObject("cloudflare")
                        ?.optJSONArray("dataChannels")

            if (channels == null || channels.length() == 0) {
                controlApplicationInProgress = false

                callback?.invoke(
                    false,
                    "Cloudflare tidak mengembalikan dataChannels: $json"
                )

                return@postJson
            }

            val channelObject =
                channels.optJSONObject(0)

            val channelId =
                channelObject?.optInt("id", -1) ?: -1

            if (channelId < 0) {
                controlApplicationInProgress = false

                callback?.invoke(
                    false,
                    "ID DataChannel controls tidak ditemukan: $json"
                )

                return@postJson
            }

            controlChannelId = channelId

            Log.d(
                TAG,
                "BUILD MARKER: CONTROL PUBLISH ALLOCATED id=$channelId 2026-09-25-C"
            )

            /*
             * datachannels/new tidak perlu SDP renegotiation lagi.
             * Transport sudah CONNECTED. Sekarang native endpoint membuat
             * negotiated channel memakai allocation ID milik publisher.
             */
            controlHandler.post {
                createNegotiatedControlChannel(
                    channelId
                ) { createOk, createError ->

                    controlApplicationInProgress = false

                    if (!createOk) {
                        callback?.invoke(
                            false,
                            createError
                        )
                        return@createNegotiatedControlChannel
                    }

                    callback?.invoke(
                        true,
                        null
                    )
                }
            }
        }
    }

    private fun createNegotiatedControlChannel(
        channelId: Int,
        callback: ((Boolean, String?) -> Unit)?
    ) {
        val connection = peerConnection

        if (connection == null) {
            callback?.invoke(
                false,
                "PeerConnection belum tersedia"
            )
            return
        }

        try {
            controlDataChannel?.let {
                try {
                    it.unregisterObserver()
                } catch (_: Exception) {
                }

                try {
                    it.dispose()
                } catch (_: Exception) {
                }
            }

            controlDataChannel = null
            controlChannelReady = false

            val init =
                DataChannel.Init().apply {
                    ordered = true
                    negotiated = true
                    id = channelId
                }

            val channel =
                connection.createDataChannel(
                    CONTROL_CHANNEL_NAME,
                    init
                )

            if (channel == null) {
                callback?.invoke(
                    false,
                    "createDataChannel() mengembalikan null"
                )
                return
            }

            controlChannelId = channelId

            attachControlDataChannel(channel)

            Log.d(
                TAG,
                "CONTROL: negotiated channel dibuat label=${channel.label()} id=${channel.id()} state=${channel.state()}"
            )

            callback?.invoke(true, null)

        } catch (e: Exception) {
            Log.e(
                TAG,
                "CONTROL: gagal membuat negotiated DataChannel",
                e
            )

            callback?.invoke(
                false,
                e.message ?: "Unknown error"
            )
        }
    }

    private fun attachControlDataChannel(
        channel: DataChannel
    ) {
        controlDataChannel = channel

        controlChannelReady =
            channel.state() == DataChannel.State.OPEN

        channel.registerObserver(
            object : DataChannel.Observer {

                private var readySent = false

                override fun onBufferedAmountChange(
                    previousAmount: Long
                ) {
                }

                override fun onStateChange() {
                    val state = channel.state()

                    controlChannelReady =
                        state == DataChannel.State.OPEN

                    Log.d(
                        TAG,
                        "CONTROL CHANNEL STATE=$state id=${channel.id()}"
                    )

                    if (state == DataChannel.State.OPEN) {
                        Log.d(
                            TAG,
                            "BUILD MARKER: CONTROL OPEN 2026-09-25-C"
                        )

                        // Tell the browser that the Android-side channel is
                        // genuinely OPEN. The browser must not treat its own
                        // onopen event as proof that Android is ready.
                        if (!readySent) {
                            readySent = true

                            val readyJson = JSONObject().apply {
                                put("type", "control_ready")
                                put("version", 1)
                                put("ts", System.currentTimeMillis())
                            }.toString()

                            controlHandler.postDelayed({
                                try {
                                    if (channel.state() == DataChannel.State.OPEN) {
                                        val buffer = DataChannel.Buffer(
                                            ByteBuffer.wrap(
                                                readyJson.toByteArray(Charsets.UTF_8)
                                            ),
                                            false
                                        )

                                        channel.send(buffer)

                                        Log.d(
                                            TAG,
                                            "CONTROL READY handshake dikirim"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG,
                                        "Gagal mengirim CONTROL READY handshake",
                                        e
                                    )
                                }
                            }, 50L)
                        }
                    }

                    if (
                        state == DataChannel.State.CLOSED ||
                        state == DataChannel.State.CLOSING
                    ) {
                        controlChannelReady = false
                        controlSetupStarted = false
                        readySent = false
                    }
                }

                override fun onMessage(
                    buffer: DataChannel.Buffer
                ) {
                    try {
                        val bytes =
                            ByteArray(buffer.data.remaining())

                        buffer.data.get(bytes)

                        val message =
                            String(
                                bytes,
                                Charsets.UTF_8
                            )

                        Log.d(
                            TAG,
                            "CONTROL RX=$message"
                        )

                        val command =
                            JSONObject(message)

                        val executed =
                            RemoteAccessibilityService
                                .executeCommand(command)

                        Log.d(
                            TAG,
                            "CONTROL executed=$executed"
                        )

                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Gagal memproses CONTROL",
                            e
                        )
                    }
                }
            }
        )
    }

    private fun waitForControlReady(
        timeoutMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val start = System.currentTimeMillis()

        fun poll() {
            val channel = controlDataChannel

            if (
                channel != null &&
                channel.state() == DataChannel.State.OPEN
            ) {
                controlChannelReady = true
                callback(true)
                return
            }

            if (
                System.currentTimeMillis() - start >= timeoutMs
            ) {
                callback(false)
                return
            }

            controlHandler.postDelayed(
                { poll() },
                100L
            )
        }

        poll()
    }

    private fun waitForControlTransport(
        timeoutMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val start = System.currentTimeMillis()

        fun poll() {
            if (controlTransportReady) {
                callback(true)
                return
            }

            if (
                !controlTransportInProgress &&
                System.currentTimeMillis() - start > 1000L
            ) {
                callback(false)
                return
            }

            if (
                System.currentTimeMillis() - start >= timeoutMs
            ) {
                callback(false)
                return
            }

            controlHandler.postDelayed(
                { poll() },
                100L
            )
        }

        poll()
    }

    private fun waitForPeerConnectionConnected(
        timeoutMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val connection = peerConnection

        if (connection == null) {
            callback(false)
            return
        }

        val start = System.currentTimeMillis()

        fun poll() {
            when (connection.connectionState()) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    Log.d(
                        TAG,
                        "CONTROL: PeerConnection CONNECTED"
                    )

                    callback(true)
                }

                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    callback(false)
                }

                else -> {
                    if (
                        System.currentTimeMillis() - start >= timeoutMs
                    ) {
                        Log.e(
                            TAG,
                            "CONTROL: timeout menunggu PeerConnection CONNECTED, state=${connection.connectionState()}"
                        )

                        callback(false)
                    } else {
                        controlHandler.postDelayed(
                            { poll() },
                            250L
                        )
                    }
                }
            }
        }

        poll()
    }

    private fun waitForIceGathering(
        callback: () -> Unit
    ) {

        val connection =
            peerConnection

        if (connection == null) {
            callback()
            return
        }

        if (
            connection.iceGatheringState() ==
            PeerConnection.IceGatheringState.COMPLETE
        ) {
            callback()
            return
        }

        val start =
            System.currentTimeMillis()

        fun poll() {

            if (
                connection.iceGatheringState() ==
                PeerConnection.IceGatheringState.COMPLETE
            ) {
                callback()
                return
            }

            if (
                System.currentTimeMillis() - start >=
                10000L
            ) {
                Log.w(
                    TAG,
                    "ICE gathering timeout, lanjut dengan SDP sekarang"
                )
                callback()
                return
            }

            controlHandler.postDelayed(
                { poll() },
                100L
            )
        }

        poll()
    }

    private fun postJson(
        path: String,
        body: JSONObject,
        callback: (
            Boolean,
            JSONObject?,
            String?
        ) -> Unit
    ) {

        thread {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(
                        WORKER_URL + path
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
                    30000

                connection.doOutput =
                    true

                connection.outputStream.use {
                    it.write(
                        body.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }

                val code =
                    connection.responseCode

                val text =
                    if (code in 200..299) {
                        connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }
                    } else {
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: "HTTP $code"
                    }

                if (code !in 200..299) {
                    callback(
                        false,
                        null,
                        text
                    )
                    return@thread
                }

                callback(
                    true,
                    JSONObject(text),
                    null
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "HTTP POST $path gagal",
                    e
                )

                callback(
                    false,
                    null,
                    e.message ?: "HTTP error"
                )

            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun putJson(
        path: String,
        body: JSONObject,
        callback: (
            Boolean,
            JSONObject?,
            String?
        ) -> Unit
    ) {

        thread {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(
                        WORKER_URL + path
                    )
                        .openConnection()
                        as HttpURLConnection

                connection.requestMethod =
                    "PUT"

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
                    30000

                connection.doOutput =
                    true

                connection.outputStream.use {
                    it.write(
                        body.toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }

                val code =
                    connection.responseCode

                val text =
                    if (code in 200..299) {
                        connection.inputStream
                            .bufferedReader()
                            .use { it.readText() }
                    } else {
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: "HTTP $code"
                    }

                if (code !in 200..299) {
                    callback(
                        false,
                        null,
                        text
                    )
                    return@thread
                }

                val json =
                    if (text.isBlank()) {
                        JSONObject()
                    } else {
                        JSONObject(text)
                    }

                callback(
                    true,
                    json,
                    null
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "HTTP PUT $path gagal",
                    e
                )

                callback(
                    false,
                    null,
                    e.message ?: "HTTP error"
                )

            } finally {
                connection?.disconnect()
            }
        }
    }

    fun sendControlCommand(
        command: String
    ): Boolean {

        val channel =
            controlDataChannel

        if (
            channel == null ||
            channel.state() !=
                DataChannel.State.OPEN
        ) {
            return false
        }

        return try {

            channel.send(
                DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap(
                        command.toByteArray(
                            Charsets.UTF_8
                        )
                    ),
                    false
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal mengirim control command",
                e
            )

            false
        }
    }

    fun isControlChannelReady(): Boolean =
        controlChannelReady

    fun getControlChannelId(): Int? =
        controlChannelId

    // =====================================================
    // OUTBOUND RTP DIAGNOSTICS
    // =====================================================

    private fun startOutboundRtpStatsLogging() {

        if (outboundStatsLogging) {
            return
        }

        outboundStatsLogging = true

        outboundStatsHandler.removeCallbacks(
            outboundStatsRunnable
        )

        Log.d(
            TAG,
            "Memulai diagnostik outbound RTP"
        )

        outboundStatsHandler.post(
            outboundStatsRunnable
        )
    }

    private fun stopOutboundRtpStatsLogging() {

        outboundStatsLogging = false

        outboundStatsHandler.removeCallbacks(
            outboundStatsRunnable
        )
    }

    private fun logOutboundRtpStats() {

        val connection =
            peerConnection

        if (connection == null) {
            Log.d(
                TAG,
                "RTP STATS: PeerConnection=null"
            )
            return
        }

        try {

            connection.getStats(
                object :
                    org.webrtc.RTCStatsCollectorCallback {

                    override fun onStatsDelivered(
                        report: RTCStatsReport
                    ) {

                        var foundOutboundVideo = false

                        for (
                            stats: RTCStats
                            in report.statsMap.values
                        ) {

                            if (
                                stats.type != "outbound-rtp"
                            ) {
                                continue
                            }

                            val members =
                                stats.members

                            val kind =
                                members["kind"]?.toString()
                                    ?: members["mediaType"]?.toString()
                                    ?: ""

                            if (
                                kind.isNotEmpty() &&
                                kind != "video"
                            ) {
                                continue
                            }

                            foundOutboundVideo = true

                            Log.d(
                                TAG,
                                "RTP OUT video: " +
                                    "packetsSent=${members["packetsSent"]} " +
                                    "bytesSent=${members["bytesSent"]} " +
                                    "framesEncoded=${members["framesEncoded"]} " +
                                    "framesSent=${members["framesSent"]} " +
                                    "keyFramesEncoded=${members["keyFramesEncoded"]} " +
                                    "nackCount=${members["nackCount"]} " +
                                    "pliCount=${members["pliCount"]}"
                            )
                        }

                        if (!foundOutboundVideo) {
                            Log.d(
                                TAG,
                                "RTP OUT video: outbound-rtp video BELUM TERLIHAT"
                            )
                        }
                    }
                }
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membaca RTP outbound stats",
                e
            )
        }
    }

    // =====================================================
    // SET REMOTE ANSWER
    // =====================================================

    fun setRemoteAnswer(
        answer: SessionDescription,
        callback:
            (() -> Unit)? = null
    ) {

        val connection =
            peerConnection

        if (connection == null) {

            Log.e(
                TAG,
                "PeerConnection belum dibuat"
            )

            return
        }

        connection.setRemoteDescription(
            object :
                org.webrtc.SdpObserver {

                override fun onCreateSuccess(
                    description:
                        SessionDescription
                ) {
                }

                override fun onSetSuccess() {

                    Log.d(
                        TAG,
                        "Remote SDP berhasil diset"
                    )

                    callback?.invoke()
                }

                override fun onCreateFailure(
                    error: String
                ) {

                    Log.e(
                        TAG,
                        "Remote SDP create failure: $error"
                    )
                }

                override fun onSetFailure(
                    error: String
                ) {

                    Log.e(
                        TAG,
                        "Remote SDP set failure: $error"
                    )
                }
            },
            answer
        )
    }

    // =====================================================
    // ICE
    // =====================================================

    fun addIceCandidate(
        candidate: IceCandidate
    ) {

        val connection =
            peerConnection

        if (connection == null) {

            Log.e(
                TAG,
                "PeerConnection belum tersedia"
            )

            return
        }

        connection.addIceCandidate(
            candidate
        )

        Log.d(
            TAG,
            "ICE candidate ditambahkan"
        )
    }

    // =====================================================
    // GET VIDEO TRACK
    // =====================================================

    fun getVideoTrack():
        VideoTrack? {

        return videoTrack
    }

    // =====================================================
    // CAPTURE STATUS
    // =====================================================

    fun isCapturing():
        Boolean {

        return capturing
    }

    // =====================================================
    // STOP SCREEN CAPTURE
    // =====================================================

    fun stopScreenCapture() {

        videoReconnectHandler.removeCallbacksAndMessages(
            null
        )

        videoReconnectInProgress = false
        videoReconnectAttempts = 0
        videoReconnectGeneration++

        stopOutboundRtpStatsLogging()

        Log.d(
            TAG,
            "Menghentikan screen capture"
        )

        try {

            screenCapturer?.stopCapture()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal stop screen capturer",
                e
            )
        }

        try {

            screenCapturer?.dispose()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal dispose screen capturer",
                e
            )
        }

        screenCapturer =
            null

        try {

            videoTrack?.dispose()

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
