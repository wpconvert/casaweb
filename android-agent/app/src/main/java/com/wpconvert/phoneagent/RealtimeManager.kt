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
    private var controlSetupInProgress = false

    @Volatile
    private var controlSetupActive = false

    private var controlSetupGeneration = 0L

    private val controlSetupLock = Any()

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
            "BUILD MARKER: REMOTEPHONE CONTROL FIX 2026-09-24-A"
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
                                val generation =
                                    synchronized(controlSetupLock) {
                                        controlSetupGeneration
                                    }

                                attachControlDataChannel(
                                    dataChannel,
                                    generation
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

                            when (newState) {
                                PeerConnection.PeerConnectionState.CONNECTED -> {
                                    Log.d(
                                        TAG,
                                        "BUILD MARKER: PEER CONNECTED 2026-09-24-B"
                                    )

                                    Log.d(
                                        TAG,
                                        "CONTROL: PeerConnection CONNECTED -> cek setup"
                                    )

                                    controlHandler.post {
                                        requestControlSetupWhenConnected()
                                    }
                                }

                                PeerConnection.PeerConnectionState.DISCONNECTED,
                                PeerConnection.PeerConnectionState.FAILED,
                                PeerConnection.PeerConnectionState.CLOSED -> {
                                    Log.w(
                                        TAG,
                                        "CONTROL: PeerConnection state=$newState -> reset control state"
                                    )

                                    synchronized(controlSetupLock) {
                                        controlSetupActive = false
                                        controlSetupInProgress = false
                                        controlSetupGeneration++
                                    }

                                    controlChannelReady = false
                                }

                                else -> Unit
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

        val connection =
            peerConnection

        if (connection == null) {

            callback(
                false,
                null,
                "PeerConnection belum dibuat"
            )

            return
        }

        currentSessionId =
            sessionId

        Log.d(
            TAG,
            "Publish ke Cloudflare dimulai"
        )

        createOffer { offer ->

            if (offer == null) {

                callback(
                    false,
                    null,
                    "Gagal membuat SDP offer"
                )

                return@createOffer
            }

            val sdp =
                offer.description

            thread {

                try {

                    val url =
                        URL(
                            "$WORKER_URL/api/publish"
                        )

                    val connectionHttp =
                        url.openConnection()
                            as HttpURLConnection

                    connectionHttp.requestMethod =
                        "POST"

                    connectionHttp.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

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

                    val body =
                        JSONObject().apply {

                            put(
                                "sessionId",
                                sessionId
                            )

                            put(
                                "deviceId",
                                deviceId
                            )

                            put(
                                "sdp",
                                sdp
                            )

                            put(
                                "mid",
                                "0"
                            )

                            put(
                                "trackName",
                                "screen-$deviceId"
                            )
                        }

                    Log.d(
                        TAG,
                        "Mengirim SDP ke Worker"
                    )

                    Log.d(
                        TAG,
                        "Device ID: $deviceId"
                    )

                    Log.d(
                        TAG,
                        "Session ID: $sessionId"
                    )

                    connectionHttp.outputStream.use {

                        it.write(
                            body.toString()
                                .toByteArray(
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
                        "Cloudflare response code: $responseCode"
                    )

                    Log.d(
                        TAG,
                        "Cloudflare response: $responseText"
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

                    // =================================================
                    // WORKER RESPONSE
                    //
                    // {
                    //   "ok": true,
                    //   "cloudflare": {
                    //      "sessionDescription": {
                    //          "type": "answer",
                    //          "sdp": "..."
                    //      }
                    //   }
                    // }
                    // =================================================

                    val cloudflare =
                        json.optJSONObject(
                            "cloudflare"
                        )

                    if (
                        cloudflare == null
                    ) {

                        callback(
                            false,
                            null,
                            "Response Worker tidak memiliki object cloudflare: $responseText"
                        )

                        connectionHttp.disconnect()

                        return@thread
                    }

                    val sessionDescription =
                        cloudflare.optJSONObject(
                            "sessionDescription"
                        )

                    if (
                        sessionDescription == null
                    ) {

                        callback(
                            false,
                            null,
                            "Cloudflare tidak mengembalikan sessionDescription: $responseText"
                        )

                        connectionHttp.disconnect()

                        return@thread
                    }

                    val answerSdp =
                        sessionDescription.optString(
                            "sdp",
                            ""
                        )

                    if (
                        answerSdp.isEmpty()
                    ) {

                        callback(
                            false,
                            null,
                            "Cloudflare tidak mengembalikan SDP answer: $responseText"
                        )

                        connectionHttp.disconnect()

                        return@thread
                    }

                    Log.d(
                        TAG,
                        "SDP answer Cloudflare berhasil ditemukan"
                    )

                    val answer =
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            answerSdp
                        )

                    // =================================================
                    // SET REMOTE DESCRIPTION
                    // =================================================

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
                                    "Remote SDP Cloudflare berhasil diset"
                                )

                                startOutboundRtpStatsLogging()

                                callback(
                                    true,
                                    answer,
                                    null
                                )

                                Log.d(
                                    TAG,
                                    "CONTROL: video publish selesai -> menunggu PeerConnection CONNECTED"
                                )

                                controlHandler.post {
                                    requestControlSetupWhenConnected()
                                }
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {

                                Log.e(
                                    TAG,
                                    "onCreateFailure: $error"
                                )

                                callback(
                                    false,
                                    null,
                                    error
                                )
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                Log.e(
                                    TAG,
                                    "onSetFailure: $error"
                                )

                                callback(
                                    false,
                                    null,
                                    error
                                )
                            }
                        },
                        answer
                    )

                    connectionHttp.disconnect()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Gagal publish ke Cloudflare",
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
    }

    // =====================================================
    // CONTROL DATACHANNEL
    // =====================================================

    /*
     * CONTROL ARCHITECTURE
     *
     * Video dan control sengaja dipisahkan.
     *
     * Video:
     *   create session -> publish video -> connected
     *
     * Control:
     *   1. pastikan PeerConnection CONNECTED
     *   2. minta Cloudflare membuat transport DataChannel
     *      "server-events"
     *   3. terima SDP offer Cloudflare
     *   4. set remote offer
     *   5. create/set local answer
     *   6. renegotiate ke Worker
     *   7. tunggu PeerConnection CONNECTED lagi
     *   8. minta Cloudflare allocate channel lokal "controls"
     *   9. gunakan ID channel yang diberikan Cloudflare
     *  10. create negotiated DataChannel "controls"
     *
     * Semua langkah dibuat serial. Tidak ada dua mutation
     * Cloudflare yang dijalankan bersamaan pada session yang sama.
     */

    private fun requestControlSetupWhenConnected() {

        val connection = peerConnection
        val sessionId = currentSessionId

        if (sessionId.isNullOrBlank()) {
            Log.d(
                TAG,
                "CONTROL: sessionId belum tersedia"
            )
            return
        }

        if (connection == null) {
            Log.d(
                TAG,
                "CONTROL: PeerConnection belum tersedia"
            )
            return
        }

        if (connection.connectionState() !=
            PeerConnection.PeerConnectionState.CONNECTED
        ) {
            Log.d(
                TAG,
                "CONTROL: menunggu CONNECTED, state=${connection.connectionState()}"
            )

            controlHandler.postDelayed(
                { requestControlSetupWhenConnected() },
                500L
            )
            return
        }

        synchronized(controlSetupLock) {

            if (controlChannelReady &&
                controlDataChannel?.state() ==
                DataChannel.State.OPEN
            ) {
                controlSetupActive = true

                Log.d(
                    TAG,
                    "CONTROL: channel sudah OPEN, tidak perlu setup ulang"
                )

                return
            }

            if (controlSetupInProgress) {
                Log.d(
                    TAG,
                    "CONTROL: setup sedang berjalan, skip"
                )
                return
            }

            controlSetupInProgress = true
            controlSetupActive = false
            controlSetupGeneration++

            Log.d(
                TAG,
                "BUILD MARKER: CONTROL SETUP ENTERED 2026-09-24-B"
            )
        }

        val generation: Long

        synchronized(controlSetupLock) {
            generation = controlSetupGeneration
        }

        Log.d(
            TAG,
            "CONTROL: memulai setup generation=$generation session=$sessionId"
        )

        setupControlDataChannelInternal(
            sessionId = sessionId,
            generation = generation
        )
    }

    private fun finishControlSetup(
        generation: Long,
        success: Boolean,
        error: String?
    ) {

        synchronized(controlSetupLock) {

            if (generation != controlSetupGeneration) {
                Log.d(
                    TAG,
                    "CONTROL: hasil setup generation lama diabaikan"
                )
                return
            }

            controlSetupInProgress = false
            controlSetupActive = success
        }

        if (success) {

            Log.d(
                TAG,
                "CONTROL: setup berhasil generation=$generation"
            )

        } else {

            controlChannelReady = false

            Log.e(
                TAG,
                "CONTROL: setup gagal generation=$generation error=$error"
            )

            /*
             * Jangan langsung melakukan mutation kedua ketika callback
             * sebelumnya baru selesai. Beri jeda agar signaling state
             * benar-benar stabil.
             */
            controlHandler.postDelayed(
                {
                    val connection = peerConnection

                    if (
                        currentSessionId == null ||
                        connection == null
                    ) {
                        return@postDelayed
                    }

                    if (
                        connection.connectionState() ==
                        PeerConnection.PeerConnectionState.CONNECTED
                    ) {
                        requestControlSetupWhenConnected()
                    }
                },
                2000L
            )
        }
    }

    /**
     * Public compatibility wrapper.
     *
     * Kode lama dapat tetap memanggil setupControlDataChannel().
     * Setup sebenarnya tetap dijalankan oleh state machine serial di atas.
     */
    fun setupControlDataChannel(
        callback: ((Boolean, String?) -> Unit)? = null
    ) {

        requestControlSetupWhenConnected()

        controlHandler.postDelayed(
            object : Runnable {
                override fun run() {

                    val ready =
                        controlChannelReady &&
                            controlDataChannel?.state() ==
                            DataChannel.State.OPEN

                    val stillWorking =
                        synchronized(controlSetupLock) {
                            controlSetupInProgress
                        }

                    if (ready) {
                        callback?.invoke(
                            true,
                            null
                        )
                        return
                    }

                    if (!stillWorking) {
                        callback?.invoke(
                            false,
                            "Control DataChannel belum OPEN"
                        )
                        return
                    }

                    controlHandler.postDelayed(
                        this,
                        250L
                    )
                }
            },
            250L
        )
    }

    private fun setupControlDataChannelInternal(
        sessionId: String,
        generation: Long
    ) {

        val connection = peerConnection

        if (connection == null) {
            finishControlSetup(
                generation,
                false,
                "PeerConnection belum tersedia"
            )
            return
        }

        Log.d(
            TAG,
            "CONTROL STEP 1: datachannel-establish session=$sessionId"
        )

        postJson(
            "/api/datachannel-establish",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("location", "remote")
                put("dataChannelName", "server-events")
            }
        ) { ok, establishJson, error ->

            if (!isCurrentControlGeneration(generation)) {
                return@postJson
            }

            if (!ok || establishJson == null) {
                finishControlSetup(
                    generation,
                    false,
                    error ?: "DataChannel establish gagal"
                )
                return@postJson
            }

            Log.d(
                TAG,
                "CONTROL STEP 1 response=$establishJson"
            )

            val cloudflare =
                establishJson.optJSONObject("cloudflare")

            val description =
                establishJson.optJSONObject("sessionDescription")
                    ?: cloudflare?.optJSONObject("sessionDescription")

            if (description == null) {
                finishControlSetup(
                    generation,
                    false,
                    "sessionDescription DataChannel tidak ditemukan"
                )
                return@postJson
            }

            val offerSdp =
                description.optString("sdp", "")

            if (offerSdp.isBlank()) {
                finishControlSetup(
                    generation,
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

            Log.d(
                TAG,
                "CONTROL: set remote transport offer"
            )

            connection.setRemoteDescription(
                object : org.webrtc.SdpObserver {

                    override fun onCreateSuccess(
                        description: SessionDescription
                    ) {
                    }

                    override fun onSetSuccess() {

                        if (!isCurrentControlGeneration(generation)) {
                            return
                        }

                        Log.d(
                            TAG,
                            "CONTROL: remote transport offer berhasil diset"
                        )

                        connection.createAnswer(
                            object : org.webrtc.SdpObserver {

                                override fun onCreateSuccess(
                                    answer: SessionDescription
                                ) {

                                    connection.setLocalDescription(
                                        object : org.webrtc.SdpObserver {

                                            override fun onCreateSuccess(
                                                description: SessionDescription
                                            ) {
                                            }

                                            override fun onSetSuccess() {

                                                if (!isCurrentControlGeneration(
                                                        generation
                                                    )
                                                ) {
                                                    return
                                                }

                                                waitForIceGathering {

                                                    if (!isCurrentControlGeneration(
                                                            generation
                                                        )
                                                    ) {
                                                        return@waitForIceGathering
                                                    }

                                                    val localSdp =
                                                        connection.localDescription
                                                            ?.description
                                                            ?: ""

                                                    if (localSdp.isBlank()) {
                                                        finishControlSetup(
                                                            generation,
                                                            false,
                                                            "Local SDP DataChannel kosong"
                                                        )
                                                        return@waitForIceGathering
                                                    }

                                                    Log.d(
                                                        TAG,
                                                        "CONTROL: mengirim renegotiate SDP"
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
                                                    ) { renegotiateOk, _, renegotiateError ->

                                                        if (!isCurrentControlGeneration(
                                                                generation
                                                            )
                                                        ) {
                                                            return@putJson
                                                        }

                                                        if (!renegotiateOk) {
                                                            finishControlSetup(
                                                                generation,
                                                                false,
                                                                renegotiateError
                                                                    ?: "Renegotiate DataChannel gagal"
                                                            )
                                                            return@putJson
                                                        }

                                                        Log.d(
                                                            TAG,
                                                            "CONTROL STEP 2: renegotiate berhasil"
                                                        )

                                                        /*
                                                         * Jangan allocate controls
                                                         * sebelum transport benar-benar
                                                         * CONNECTED.
                                                         */
                                                        waitForPeerConnectionConnected(
                                                            generation
                                                        ) {

                                                            if (!isCurrentControlGeneration(
                                                                    generation
                                                                )
                                                            ) {
                                                                return@waitForPeerConnectionConnected
                                                            }

                                                            Log.d(
                                                                TAG,
                                                                "CONTROL STEP 3: transport CONNECTED -> publish controls"
                                                            )

                                                            createPublisherControlChannel(
                                                                sessionId,
                                                                generation
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            override fun onCreateFailure(
                                                error: String
                                            ) {
                                                finishControlSetup(
                                                    generation,
                                                    false,
                                                    "Local SDP create failure: $error"
                                                )
                                            }

                                            override fun onSetFailure(
                                                error: String
                                            ) {
                                                finishControlSetup(
                                                    generation,
                                                    false,
                                                    "Local SDP set failure: $error"
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
                                    finishControlSetup(
                                        generation,
                                        false,
                                        "Answer create failure: $error"
                                    )
                                }

                                override fun onSetFailure(
                                    error: String
                                ) {
                                    finishControlSetup(
                                        generation,
                                        false,
                                        "Answer set failure: $error"
                                    )
                                }
                            },
                            MediaConstraints()
                        )
                    }

                    override fun onCreateFailure(
                        error: String
                    ) {
                        finishControlSetup(
                            generation,
                            false,
                            "Remote offer create failure: $error"
                        )
                    }

                    override fun onSetFailure(
                        error: String
                    ) {
                        finishControlSetup(
                            generation,
                            false,
                            "Remote offer set failure: $error"
                        )
                    }
                },
                offer
            )
        }
    }

    private fun waitForPeerConnectionConnected(
        generation: Long,
        callback: () -> Unit
    ) {

        val connection = peerConnection

        if (connection == null) {
            finishControlSetup(
                generation,
                false,
                "PeerConnection null saat menunggu CONNECTED"
            )
            return
        }

        val start = System.currentTimeMillis()

        fun poll() {

            if (!isCurrentControlGeneration(generation)) {
                return
            }

            val state =
                connection.connectionState()

            if (
                state ==
                PeerConnection.PeerConnectionState.CONNECTED
            ) {
                Log.d(
                    TAG,
                    "CONTROL: PeerConnection CONNECTED setelah renegotiate"
                )

                callback()
                return
            }

            if (
                state ==
                PeerConnection.PeerConnectionState.FAILED ||
                state ==
                PeerConnection.PeerConnectionState.CLOSED
            ) {
                finishControlSetup(
                    generation,
                    false,
                    "PeerConnection state=$state setelah renegotiate"
                )
                return
            }

            if (
                System.currentTimeMillis() - start >=
                20000L
            ) {
                finishControlSetup(
                    generation,
                    false,
                    "Timeout menunggu PeerConnection CONNECTED, state=$state"
                )
                return
            }

            controlHandler.postDelayed(
                { poll() },
                250L
            )
        }

        poll()
    }

    private fun createPublisherControlChannel(
        sessionId: String,
        generation: Long
    ) {

        if (!isCurrentControlGeneration(generation)) {
            return
        }

        postJson(
            "/api/datachannel-publish",
            JSONObject().apply {
                put(
                    "sessionId",
                    sessionId
                )
                put(
                    "dataChannelName",
                    CONTROL_CHANNEL_NAME
                )
                put(
                    "ordered",
                    true
                )
            }
        ) { ok, json, error ->

            if (!isCurrentControlGeneration(generation)) {
                return@postJson
            }

            Log.d(
                TAG,
                "CONTROL STEP 4: datachannel-publish ok=$ok json=$json error=$error"
            )

            if (!ok || json == null) {

                finishControlSetup(
                    generation,
                    false,
                    error ?: "DataChannel publish gagal"
                )

                return@postJson
            }

            /*
             * Worker saat ini biasanya meneruskan response Cloudflare
             * langsung. Tetap dukung response yang dibungkus object
             * "cloudflare" supaya perubahan kecil pada Worker tidak
             * mematikan control.
             */
            val cloudflare =
                json.optJSONObject("cloudflare")

            val channels =
                json.optJSONArray("dataChannels")
                    ?: cloudflare?.optJSONArray("dataChannels")

            if (
                channels == null ||
                channels.length() == 0
            ) {
                finishControlSetup(
                    generation,
                    false,
                    "Cloudflare tidak mengembalikan dataChannels: $json"
                )
                return@postJson
            }

            val channelObject =
                channels.optJSONObject(0)

            if (channelObject == null) {
                finishControlSetup(
                    generation,
                    false,
                    "Object DataChannel controls kosong"
                )
                return@postJson
            }

            val channelId =
                channelObject.optInt(
                    "id",
                    -1
                )

            Log.d(
                TAG,
                "CONTROL STEP 5: Cloudflare controls channelId=$channelId object=$channelObject"
            )

            if (channelId < 0) {
                finishControlSetup(
                    generation,
                    false,
                    "ID DataChannel controls tidak ditemukan: $channelObject"
                )
                return@postJson
            }

            /*
             * Penting:
             * Tidak melakukan renegotiation SDP lagi di sini.
             * Channel application ini adalah negotiated channel
             * menggunakan ID yang sudah dialokasikan Cloudflare.
             */
            createNegotiatedControlChannel(
                channelId = channelId,
                generation = generation
            )
        }
    }

    private fun createNegotiatedControlChannel(
        channelId: Int,
        generation: Long
    ) {

        val connection = peerConnection

        if (connection == null) {
            finishControlSetup(
                generation,
                false,
                "PeerConnection belum tersedia"
            )
            return
        }

        if (!isCurrentControlGeneration(generation)) {
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
            controlChannelId = channelId

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
                finishControlSetup(
                    generation,
                    false,
                    "createDataChannel() mengembalikan null"
                )
                return
            }

            attachControlDataChannel(
                channel,
                generation
            )

            Log.d(
                TAG,
                "CONTROL STEP 6: controls dibuat id=$channelId state=${channel.state()}"
            )

            /*
             * createDataChannel() dapat mengembalikan CONNECTING.
             * Tunggu sampai OPEN sebelum menganggap control benar-benar
             * aktif.
             */
            waitForControlChannelOpen(
                channel,
                generation
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membuat negotiated control DataChannel",
                e
            )

            finishControlSetup(
                generation,
                false,
                e.message ?: "Unknown error"
            )
        }
    }

    private fun waitForControlChannelOpen(
        channel: DataChannel,
        generation: Long
    ) {

        val start = System.currentTimeMillis()

        fun poll() {

            if (!isCurrentControlGeneration(generation)) {
                return
            }

            if (
                channel.state() ==
                DataChannel.State.OPEN
            ) {
                controlChannelReady = true
                controlSetupActive = true

                finishControlSetup(
                    generation,
                    true,
                    null
                )

                Log.d(
                    TAG,
                    "BUILD MARKER: CONTROL CHANNEL OPEN 2026-09-24-B id=${channel.id()}"
                )

                return
            }

            if (
                channel.state() ==
                DataChannel.State.CLOSED
            ) {
                finishControlSetup(
                    generation,
                    false,
                    "Control DataChannel CLOSED sebelum OPEN"
                )
                return
            }

            if (
                System.currentTimeMillis() - start >=
                15000L
            ) {
                finishControlSetup(
                    generation,
                    false,
                    "Timeout menunggu control DataChannel OPEN, state=${channel.state()}"
                )
                return
            }

            controlHandler.postDelayed(
                { poll() },
                250L
            )
        }

        poll()
    }

    private fun isCurrentControlGeneration(
        generation: Long
    ): Boolean {

        synchronized(controlSetupLock) {
            return generation == controlSetupGeneration
        }
    }

    private fun attachControlDataChannel(
        channel: DataChannel,
        generation: Long
    ) {

        controlDataChannel =
            channel

        controlChannelReady =
            channel.state() ==
                DataChannel.State.OPEN

        channel.registerObserver(
            object : DataChannel.Observer {

                override fun onBufferedAmountChange(
                    previousAmount: Long
                ) {
                    // Tidak perlu tindakan.
                }

                override fun onStateChange() {

                    val state =
                        channel.state()

                    controlChannelReady =
                        state ==
                            DataChannel.State.OPEN

                    Log.d(
                        TAG,
                        "CONTROL DataChannel state=$state id=${channel.id()}"
                    )

                    if (
                        state ==
                        DataChannel.State.OPEN
                    ) {
                        synchronized(controlSetupLock) {
                            if (
                                generation ==
                                controlSetupGeneration
                            ) {
                                controlSetupActive = true
                            }
                        }

                        return
                    }

                    if (
                        state ==
                        DataChannel.State.CLOSED ||
                        state ==
                        DataChannel.State.CLOSING
                    ) {

                        synchronized(controlSetupLock) {

                            if (
                                generation ==
                                controlSetupGeneration
                            ) {
                                controlChannelReady = false
                                controlSetupActive = false
                                controlSetupInProgress = false
                                controlSetupGeneration++
                            }
                        }

                        /*
                         * Tunggu sebentar sebelum membuat channel baru.
                         * Ini mencegah mutation bertubi-tubi ketika transport
                         * sedang berubah.
                         */
                        controlHandler.postDelayed(
                            {
                                requestControlSetupWhenConnected()
                            },
                            2000L
                        )
                    }
                }

                override fun onMessage(
                    buffer: DataChannel.Buffer
                ) {

                    try {

                        val bytes =
                            ByteArray(
                                buffer.data.remaining()
                            )

                        buffer.data.get(
                            bytes
                        )

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
                            JSONObject(
                                message
                            )

                        val executed =
                            RemoteAccessibilityService
                                .executeCommand(
                                    command
                                )

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

        synchronized(controlSetupLock) {
            controlSetupGeneration++
            controlSetupInProgress = false
            controlSetupActive = false
        }

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

        currentSessionId =
            null

        Log.d(
            TAG,
            "RealtimeManager selesai dispose"
        )
    }
}
