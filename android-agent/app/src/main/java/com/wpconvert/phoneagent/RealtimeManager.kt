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

    private val controlHandler =
        Handler(Looper.getMainLooper())

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

                                callback(
                                    true,
                                    answer,
                                    null
                                )

                                setupControlDataChannel { ok, error ->
                                    if (ok) {
                                        Log.d(
                                            TAG,
                                            "Control DataChannel siap"
                                        )
                                    } else {
                                        Log.e(
                                            TAG,
                                            "Control DataChannel gagal: $error"
                                        )
                                    }
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

    /**
     * Menyiapkan jalur DataChannel kontrol Android.
     *
     * Alurnya:
     * 1. establish transport DataChannel dari Worker
     * 2. renegotiate PeerConnection
     * 3. minta Cloudflare membuat application channel "controls"
     * 4. buat negotiated DataChannel dengan ID dari Cloudflare
     */
    fun setupControlDataChannel(
        callback: ((Boolean, String?) -> Unit)? = null
    ) {

        val sessionId =
            currentSessionId

        val connection =
            peerConnection

        if (sessionId.isNullOrBlank()) {
            callback?.invoke(
                false,
                "SessionId belum tersedia"
            )
            return
        }

        if (connection == null) {
            callback?.invoke(
                false,
                "PeerConnection belum tersedia"
            )
            return
        }

        Log.d(
            TAG,
            "Menyiapkan Control DataChannel..."
        )

        postJson(
            "/api/datachannel-establish",
            JSONObject().apply {
                put("sessionId", sessionId)
                put("location", "remote")
                put(
                    "dataChannelName",
                    "server-events"
                )
            }
        ) { ok, establishJson, error ->

            if (!ok || establishJson == null) {
                callback?.invoke(
                    false,
                    error ?: "DataChannel establish gagal"
                )
                return@postJson
            }

            val cloudflare =
                establishJson.optJSONObject(
                    "cloudflare"
                )

            val description =
                establishJson.optJSONObject(
                    "sessionDescription"
                )
                    ?: cloudflare?.optJSONObject(
                        "sessionDescription"
                    )

            if (description == null) {
                callback?.invoke(
                    false,
                    "sessionDescription DataChannel tidak ditemukan"
                )
                return@postJson
            }

            val offerSdp =
                description.optString(
                    "sdp",
                    ""
                )

            if (offerSdp.isBlank()) {
                callback?.invoke(
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

            connection.setRemoteDescription(
                object :
                    org.webrtc.SdpObserver {

                    override fun onCreateSuccess(
                        description:
                            SessionDescription
                    ) {
                    }

                    override fun onSetSuccess() {

                        connection.createAnswer(
                            object :
                                org.webrtc.SdpObserver {

                                override fun onCreateSuccess(
                                    answer:
                                        SessionDescription
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
                                                        callback?.invoke(
                                                            false,
                                                            "Local SDP DataChannel kosong"
                                                        )
                                                        return@waitForIceGathering
                                                    }

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

                                                        if (!renegotiateOk) {
                                                            callback?.invoke(
                                                                false,
                                                                renegotiateError
                                                                    ?: "Renegotiate DataChannel gagal"
                                                            )
                                                            return@putJson
                                                        }

                                                        createPublisherControlChannel(
                                                            sessionId,
                                                            callback
                                                        )
                                                    }
                                                }
                                            }

                                            override fun onCreateFailure(
                                                error: String
                                            ) {
                                                callback?.invoke(
                                                    false,
                                                    error
                                                )
                                            }

                                            override fun onSetFailure(
                                                error: String
                                            ) {
                                                callback?.invoke(
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
                                    callback?.invoke(
                                        false,
                                        error
                                    )
                                }

                                override fun onSetFailure(
                                    error: String
                                ) {
                                }
                            },
                            MediaConstraints()
                        )
                    }

                    override fun onCreateFailure(
                        error: String
                    ) {
                        callback?.invoke(
                            false,
                            error
                        )
                    }

                    override fun onSetFailure(
                        error: String
                    ) {
                        callback?.invoke(
                            false,
                            error
                        )
                    }
                },
                offer
            )
        }
    }

    private fun createPublisherControlChannel(
        sessionId: String,
        callback: ((Boolean, String?) -> Unit)?
    ) {

        postJson(
            "/api/datachannel-publish",
            JSONObject().apply {
                put("sessionId", sessionId)
                put(
                    "dataChannelName",
                    CONTROL_CHANNEL_NAME
                )
            }
        ) { ok, json, error ->

            if (!ok || json == null) {
                callback?.invoke(
                    false,
                    error ?: "DataChannel publish gagal"
                )
                return@postJson
            }

            val channels =
                json.optJSONArray(
                    "dataChannels"
                )

            if (
                channels == null ||
                channels.length() == 0
            ) {
                callback?.invoke(
                    false,
                    "Cloudflare tidak mengembalikan dataChannels"
                )
                return@postJson
            }

            val channelObject =
                channels.optJSONObject(0)

            val channelId =
                channelObject?.optInt(
                    "id",
                    -1
                ) ?: -1

            if (channelId < 0) {
                callback?.invoke(
                    false,
                    "ID DataChannel controls tidak ditemukan"
                )
                return@postJson
            }

            createNegotiatedControlChannel(
                channelId,
                callback
            )
        }
    }

    private fun createNegotiatedControlChannel(
        channelId: Int,
        callback: ((Boolean, String?) -> Unit)?
    ) {

        val connection =
            peerConnection

        if (connection == null) {
            callback?.invoke(
                false,
                "PeerConnection belum tersedia"
            )
            return
        }

        try {

            controlDataChannel?.dispose()

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
                callback?.invoke(
                    false,
                    "createDataChannel() mengembalikan null"
                )
                return
            }

            attachControlDataChannel(
                channel
            )

            Log.d(
                TAG,
                "Control DataChannel dibuat id=$channelId state=${channel.state()}"
            )

            callback?.invoke(
                true,
                null
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membuat negotiated control DataChannel",
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
                        "Control DataChannel state=$state id=${channel.id()}"
                    )
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
