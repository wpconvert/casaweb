package com.wpconvert.phoneagent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
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

        private const val SERVER_EVENTS_CHANNEL_NAME =
            "server-events"

        private const val CONTROL_CHANNEL_ID_UNKNOWN =
            -1
    }

    // =========================================================
    // WEBRTC
    // =========================================================

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

    // =========================================================
    // STATE
    // =========================================================

    private var initialized =
        false

    private var capturing =
        false

    private var currentSessionId:
        String? = null

    // =========================================================
    // CONTROL DATACHANNEL
    // =========================================================

    private var controlDataChannel:
        DataChannel? = null

    private var controlChannelId:
        Int? = null

    @Volatile
    private var controlChannelReady =
        false

    // =========================================================
    // INITIALIZE
    // =========================================================

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
                    .setEnableInternalTracer(false)
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

    // =========================================================
    // SCREEN CAPTURE
    // =========================================================

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

            val eglBase =
                org.webrtc.EglBase.create()

            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    "ScreenCaptureThread",
                    eglBase.eglBaseContext
                )

            videoSource =
                factory.createVideoSource(true)

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

            val capturerObserver =
                object : org.webrtc.CapturerObserver {

                    private var frameCount =
                        0L

                    override fun onCapturerStarted(
                        success: Boolean
                    ) {

                        Log.d(
                            TAG,
                            "CapturerObserver.onCapturerStarted: $success"
                        )
                    }

                    override fun onCapturerStopped() {

                        Log.d(
                            TAG,
                            "CapturerObserver.onCapturerStopped"
                        )
                    }

                    override fun onFrameCaptured(
                        frame: VideoFrame
                    ) {

                        frameCount++

                        if (frameCount == 1L) {

                            Log.d(
                                TAG,
                                "========================================"
                            )

                            Log.d(
                                TAG,
                                "FRAME PERTAMA DITERIMA"
                            )

                            Log.d(
                                TAG,
                                "size=${frame.buffer.width}x${frame.buffer.height}"
                            )

                            Log.d(
                                TAG,
                                "rotation=${frame.rotation}"
                            )

                            Log.d(
                                TAG,
                                "========================================"
                            )
                        }

                        if (frameCount % 30L == 0L) {

                            Log.d(
                                TAG,
                                "FRAME #$frameCount " +
                                    "${frame.buffer.width}x${frame.buffer.height}"
                            )
                        }

                        source.capturerObserver
                            .onFrameCaptured(frame)
                    }
                }

            capturer.initialize(
                helper,
                context.applicationContext,
                capturerObserver
            )

            source.adaptOutputFormat(
                720,
                1600,
                30
            )

            Log.d(
                TAG,
                "Memulai capture 720x1600 @ 30 FPS"
            )

            capturer.startCapture(
                720,
                1600,
                30
            )

            videoTrack =
                factory.createVideoTrack(
                    "screen-track",
                    source
                )

            videoTrack?.setEnabled(true)

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

    // =========================================================
    // PEER CONNECTION
    // =========================================================

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
                PeerConnection.SdpSemantics.UNIFIED_PLAN

            peerConnection =
                factory.createPeerConnection(
                    configuration,
                    object : PeerConnection.Observer {

                        override fun onSignalingChange(
                            state: PeerConnection.SignalingState
                        ) {

                            Log.d(
                                TAG,
                                "Signaling state: $state"
                            )
                        }

                        override fun onIceConnectionChange(
                            state: PeerConnection.IceConnectionState
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
                            state: PeerConnection.IceGatheringState
                        ) {

                            Log.d(
                                TAG,
                                "ICE gathering state: $state"
                            )
                        }

                        override fun onIceCandidate(
                            candidate: IceCandidate
                        ) {

                            Log.d(
                                TAG,
                                "ICE candidate received"
                            )
                        }

                        override fun onIceCandidatesRemoved(
                            candidates: Array<out IceCandidate>
                        ) {

                            Log.d(
                                TAG,
                                "ICE candidates removed"
                            )
                        }

                        override fun onAddStream(
                            stream: org.webrtc.MediaStream
                        ) {

                            Log.d(
                                TAG,
                                "Remote stream ditambahkan"
                            )
                        }

                        override fun onRemoveStream(
                            stream: org.webrtc.MediaStream
                        ) {

                            Log.d(
                                TAG,
                                "Remote stream dihapus"
                            )
                        }

                        override fun onDataChannel(
                            dataChannel: DataChannel
                        ) {

                            Log.d(
                                TAG,
                                "DataChannel diterima: " +
                                    dataChannel.label()
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
                            receiver: org.webrtc.RtpReceiver,
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
                            event: CandidatePairChangeEvent
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

            videoTrack?.let { track ->

                peerConnection?.addTrack(
                    track,
                    listOf("screen-stream")
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

    // =========================================================
    // CREATE OFFER
    // =========================================================

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
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {

                    Log.d(
                        TAG,
                        "SDP Offer berhasil dibuat"
                    )

                    connection.setLocalDescription(
                        object : SdpObserver {

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

                                waitForIceGathering {
                                    val local =
                                        connection.localDescription

                                    if (local != null) {
                                        callback(local)
                                    } else {
                                        callback(description)
                                    }
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

    // =========================================================
    // CREATE CLOUDFLARE SESSION
    // =========================================================

    fun createCloudflareSession(
        callback:
            (
                success: Boolean,
                sessionId: String?,
                error: String?
            ) -> Unit
    ) {

        Log.d(
            TAG,
            "Meminta Cloudflare session baru"
        )

        thread {

            try {

                val response =
                    httpRequest(
                        method = "POST",
                        path = "/api/session",
                        body = "{}"
                    )

                Log.d(
                    TAG,
                    "Session response code: ${response.code}"
                )

                Log.d(
                    TAG,
                    "Session response: ${response.body}"
                )

                if (
                    response.code !in 200..299
                ) {

                    callback(
                        false,
                        null,
                        response.body
                    )

                    return@thread
                }

                val json =
                    JSONObject(response.body)

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
                        "Cloudflare tidak mengembalikan sessionId"
                    )

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

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gagal membuat Cloudflare session",
                    e
                )

                callback(
                    false,
                    null,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    // =========================================================
    // PUBLISH VIDEO
    // =========================================================

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

            thread {

                try {

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
                                offer.description
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

                    val response =
                        httpRequest(
                            method = "POST",
                            path = "/api/publish",
                            body = body.toString()
                        )

                    Log.d(
                        TAG,
                        "Cloudflare response code: ${response.code}"
                    )

                    Log.d(
                        TAG,
                        "Cloudflare response: ${response.body}"
                    )

                    if (
                        response.code !in 200..299
                    ) {

                        callback(
                            false,
                            null,
                            response.body
                        )

                        return@thread
                    }

                    val json =
                        JSONObject(response.body)

                    val cloudflare =
                        json.optJSONObject(
                            "cloudflare"
                        )

                    val sessionDescription =
                        cloudflare?.optJSONObject(
                            "sessionDescription"
                        )

                    val answerSdp =
                        sessionDescription?.optString(
                            "sdp",
                            ""
                        ) ?: ""

                    if (
                        answerSdp.isEmpty()
                    ) {

                        callback(
                            false,
                            null,
                            "Cloudflare tidak mengembalikan SDP answer"
                        )

                        return@thread
                    }

                    val answer =
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            answerSdp
                        )

                    connection.setRemoteDescription(
                        object : SdpObserver {

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
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {

                                callback(
                                    false,
                                    null,
                                    error
                                )
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                callback(
                                    false,
                                    null,
                                    error
                                )
                            }
                        },
                        answer
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Gagal publish ke Cloudflare",
                        e
                    )

                    callback(
                        false,
                        null,
                        e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    // =========================================================
    // DATA CHANNEL - FULL CLOUDFLARE SETUP
    // =========================================================

    /**
     * Jalur:
     *
     * 1. /api/datachannel-establish
     * 2. Cloudflare memberikan SDP offer baru
     * 3. Android setRemoteDescription()
     * 4. Android createAnswer()
     * 5. Android setLocalDescription()
     * 6. Tunggu ICE gathering
     * 7. /api/renegotiate
     * 8. /api/datachannel-publish
     * 9. Ambil DataChannel ID
     * 10. createDataChannel(... negotiated=true ...)
     */
    fun setupControlDataChannel(
        callback:
            ((Boolean, String?) -> Unit)? = null
    ) {

        val sessionId =
            currentSessionId

        val connection =
            peerConnection

        if (sessionId.isNullOrEmpty()) {

            callback?.invoke(
                false,
                "Cloudflare sessionId belum tersedia"
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

        thread {

            try {

                Log.d(
                    TAG,
                    "Memulai establish DataChannel transport"
                )

                val establishBody =
                    JSONObject().apply {

                        put(
                            "sessionId",
                            sessionId
                        )

                        put(
                            "location",
                            "remote"
                        )

                        put(
                            "dataChannelName",
                            SERVER_EVENTS_CHANNEL_NAME
                        )
                    }

                val establishResponse =
                    httpRequest(
                        method = "POST",
                        path = "/api/datachannel-establish",
                        body = establishBody.toString()
                    )

                Log.d(
                    TAG,
                    "DataChannel establish HTTP: " +
                        establishResponse.code
                )

                Log.d(
                    TAG,
                    "DataChannel establish response: " +
                        establishResponse.body
                )

                if (
                    establishResponse.code !in 200..299
                ) {

                    callback?.invoke(
                        false,
                        establishResponse.body
                    )

                    return@thread
                }

                val establishJson =
                    JSONObject(
                        establishResponse.body
                    )

                val sessionDescription =
                    establishJson.optJSONObject(
                        "sessionDescription"
                    )

                if (
                    sessionDescription == null
                ) {

                    callback?.invoke(
                        false,
                        "Cloudflare tidak memberikan sessionDescription untuk DataChannel"
                    )

                    return@thread
                }

                val offerSdp =
                    sessionDescription.optString(
                        "sdp",
                        ""
                    )

                if (
                    offerSdp.isEmpty()
                ) {

                    callback?.invoke(
                        false,
                        "SDP DataChannel offer kosong"
                    )

                    return@thread
                }

                val dataChannelOffer =
                    SessionDescription(
                        SessionDescription.Type.OFFER,
                        offerSdp
                    )

                runOnPeerThread {

                    connection.setRemoteDescription(
                        object : SdpObserver {

                            override fun onCreateSuccess(
                                description:
                                    SessionDescription
                            ) {
                            }

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "DataChannel SFU offer berhasil diset"
                                )

                                createDataChannelAnswer(
                                    connection
                                ) { success, answer, error ->

                                    if (!success || answer == null) {

                                        callback?.invoke(
                                            false,
                                            error
                                                ?: "Gagal membuat DataChannel answer"
                                        )

                                        return@createDataChannelAnswer
                                    }

                                    renegotiateDataChannel(
                                        sessionId,
                                        answer
                                    ) { renegotiateSuccess, renegotiateError ->

                                        if (!renegotiateSuccess) {

                                            callback?.invoke(
                                                false,
                                                renegotiateError
                                                    ?: "DataChannel renegotiate gagal"
                                            )

                                            return@renegotiateDataChannel
                                        }

                                        createPublisherControlChannel(
                                            sessionId
                                        ) { channelSuccess, channelId, channelError ->

                                            if (!channelSuccess) {

                                                callback?.invoke(
                                                    false,
                                                    channelError
                                                        ?: "Gagal membuat controls DataChannel"
                                                )

                                                return@createPublisherControlChannel
                                            }

                                            if (
                                                channelId == null
                                            ) {

                                                callback?.invoke(
                                                    false,
                                                    "Cloudflare tidak memberikan channel ID"
                                                )

                                                return@createPublisherControlChannel
                                            }

                                            createNegotiatedControlChannel(
                                                channelId
                                            ) { finalSuccess, finalError ->

                                                callback?.invoke(
                                                    finalSuccess,
                                                    finalError
                                                )
                                            }
                                        }
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
                        dataChannelOffer
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gagal setup DataChannel",
                    e
                )

                callback?.invoke(
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    // =========================================================
    // DATA CHANNEL - CREATE ANSWER
    // =========================================================

    private fun createDataChannelAnswer(
        connection: PeerConnection,
        callback:
            (Boolean, SessionDescription?, String?) -> Unit
    ) {

        val constraints =
            MediaConstraints()

        connection.createAnswer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    answer: SessionDescription
                ) {

                    connection.setLocalDescription(
                        object : SdpObserver {

                            override fun onCreateSuccess(
                                description:
                                    SessionDescription
                            ) {
                            }

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "DataChannel local answer berhasil diset"
                                )

                                waitForIceGathering {

                                    val local =
                                        connection.localDescription

                                    if (local == null) {

                                        callback(
                                            false,
                                            null,
                                            "localDescription null setelah DataChannel answer"
                                        )

                                    } else {

                                        callback(
                                            true,
                                            local,
                                            null
                                        )
                                    }
                                }
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {

                                callback(
                                    false,
                                    null,
                                    error
                                )
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                callback(
                                    false,
                                    null,
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

                    callback(
                        false,
                        null,
                        error
                    )
                }

                override fun onSetFailure(
                    error: String
                ) {
                }
            },
            constraints
        )
    }

    // =========================================================
    // DATA CHANNEL - RENEGOTIATE
    // =========================================================

    private fun renegotiateDataChannel(
        sessionId: String,
        answer: SessionDescription,
        callback:
            (Boolean, String?) -> Unit
    ) {

        thread {

            try {

                val body =
                    JSONObject().apply {

                        put(
                            "sessionId",
                            sessionId
                        )

                        put(
                            "sdp",
                            answer.description
                        )
                    }

                val response =
                    httpRequest(
                        method = "PUT",
                        path = "/api/renegotiate",
                        body = body.toString()
                    )

                Log.d(
                    TAG,
                    "DataChannel renegotiate HTTP: " +
                        response.code
                )

                Log.d(
                    TAG,
                    "DataChannel renegotiate response: " +
                        response.body
                )

                if (
                    response.code !in 200..299
                ) {

                    callback(
                        false,
                        response.body
                    )

                    return@thread
                }

                callback(
                    true,
                    null
                )

            } catch (e: Exception) {

                callback(
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    // =========================================================
    // DATA CHANNEL - ALLOCATE CONTROLS
    // =========================================================

    private fun createPublisherControlChannel(
        sessionId: String,
        callback:
            (Boolean, Int?, String?) -> Unit
    ) {

        thread {

            try {

                val body =
                    JSONObject().apply {

                        put(
                            "sessionId",
                            sessionId
                        )

                        put(
                            "dataChannelName",
                            CONTROL_CHANNEL_NAME
                        )
                    }

                val response =
                    httpRequest(
                        method = "POST",
                        path = "/api/datachannel-publish",
                        body = body.toString()
                    )

                Log.d(
                    TAG,
                    "Control channel allocation HTTP: " +
                        response.code
                )

                Log.d(
                    TAG,
                    "Control channel allocation response: " +
                        response.body
                )

                if (
                    response.code !in 200..299
                ) {

                    callback(
                        false,
                        null,
                        response.body
                    )

                    return@thread
                }

                val json =
                    JSONObject(
                        response.body
                    )

                val channels =
                    json.optJSONArray(
                        "dataChannels"
                    )

                if (
                    channels == null ||
                    channels.length() == 0
                ) {

                    callback(
                        false,
                        null,
                        "Cloudflare tidak mengembalikan dataChannels"
                    )

                    return@thread
                }

                val channel =
                    channels.getJSONObject(0)

                val id =
                    channel.optInt(
                        "id",
                        CONTROL_CHANNEL_ID_UNKNOWN
                    )

                if (
                    id < 0
                ) {

                    callback(
                        false,
                        null,
                        "Cloudflare mengembalikan channel ID tidak valid"
                    )

                    return@thread
                }

                controlChannelId =
                    id

                Log.d(
                    TAG,
                    "Cloudflare control channel ID: $id"
                )

                callback(
                    true,
                    id,
                    null
                )

            } catch (e: Exception) {

                callback(
                    false,
                    null,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    // =========================================================
    // DATA CHANNEL - CREATE NEGOTIATED CHANNEL
    // =========================================================

    private fun createNegotiatedControlChannel(
        channelId: Int,
        callback:
            (Boolean, String?) -> Unit
    ) {

        val connection =
            peerConnection

        if (connection == null) {

            callback(
                false,
                "PeerConnection belum tersedia"
            )

            return
        }

        runOnPeerThread {

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

                controlDataChannel =
                    null

                controlChannelReady =
                    false

                controlChannelId =
                    channelId

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

                    callback(
                        false,
                        "createDataChannel() mengembalikan null"
                    )

                    return@runOnPeerThread
                }

                attachControlDataChannel(
                    channel
                )

                Log.d(
                    TAG,
                    "controls DataChannel dibuat: " +
                        "id=$channelId " +
                        "state=${channel.state()}"
                )

                callback(
                    true,
                    null
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gagal membuat negotiated controls DataChannel",
                    e
                )

                callback(
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    // =========================================================
    // ATTACH CONTROL CHANNEL
    // =========================================================

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

                    Log.d(
                        TAG,
                        "Control DataChannel buffered amount: " +
                            previousAmount
                    )
                }

                override fun onStateChange() {

                    val state =
                        channel.state()

                    controlChannelReady =
                        state ==
                            DataChannel.State.OPEN

                    Log.d(
                        TAG,
                        "Control DataChannel state: $state"
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
                            "Control DataChannel message: $message"
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "Gagal membaca control message",
                            e
                        )
                    }
                }
            }
        )
    }

    // =========================================================
    // SEND CONTROL COMMAND
    // =========================================================

    fun sendControlCommand(
        command: String
    ): Boolean {

        val channel =
            controlDataChannel

        if (channel == null) {

            Log.e(
                TAG,
                "Control DataChannel belum dibuat"
            )

            return false
        }

        if (
            channel.state() !=
            DataChannel.State.OPEN
        ) {

            Log.e(
                TAG,
                "Control DataChannel belum OPEN: " +
                    channel.state()
            )

            return false
        }

        return try {

            val buffer =
                ByteBuffer.wrap(
                    command.toByteArray(
                        Charsets.UTF_8
                    )
                )

            val sent =
                channel.send(
                    DataChannel.Buffer(
                        buffer,
                        false
                    )
                )

            Log.d(
                TAG,
                "Control command dikirim: " +
                    "$command sent=$sent"
            )

            sent

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal mengirim control command",
                e
            )

            false
        }
    }

    // =========================================================
    // CONTROL STATUS
    // =========================================================

    fun isControlChannelReady():
        Boolean {

        return controlChannelReady
    }

    fun getControlChannelId():
        Int? {

        return controlChannelId
    }

    // =========================================================
    // SET REMOTE ANSWER
    // =========================================================

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
            object : SdpObserver {

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

    // =========================================================
    // ICE
    // =========================================================

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

    // =========================================================
    // VIDEO TRACK
    // =========================================================

    fun getVideoTrack():
        VideoTrack? {

        return videoTrack
    }

    // =========================================================
    // CAPTURE STATUS
    // =========================================================

    fun isCapturing():
        Boolean {

        return capturing
    }

    // =========================================================
    // SCREEN CAPTURE STOP
    // =========================================================

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

    // =========================================================
    // DISPOSE
    // =========================================================

    fun dispose() {

        Log.d(
            TAG,
            "Dispose RealtimeManager"
        )

        stopScreenCapture()

        try {

            controlDataChannel
                ?.unregisterObserver()

            controlDataChannel
                ?.dispose()

        } catch (_: Exception) {
        }

        controlDataChannel =
            null

        controlChannelId =
            null

        controlChannelReady =
            false

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

        currentSessionId =
            null

        Log.d(
            TAG,
            "RealtimeManager selesai dispose"
        )
    }

    // =========================================================
    // HTTP HELPER
    // =========================================================

    private data class HttpResult(
        val code: Int,
        val body: String
    )

    private fun httpRequest(
        method: String,
        path: String,
        body: String? = null
    ): HttpResult {

        val url =
            URL(
                "$WORKER_URL$path"
            )

        val connection =
            url.openConnection()
                as HttpURLConnection

        try {

            connection.requestMethod =
                method

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.connectTimeout =
                15000

            connection.readTimeout =
                30000

            if (
                body != null &&
                (
                    method == "POST" ||
                    method == "PUT"
                )
            ) {

                connection.doOutput =
                    true

                connection.outputStream.use {
                    it.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }
            }

            val code =
                connection.responseCode

            val text =
                if (code in 200..299) {

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
                        ?: "HTTP $code"
                }

            return HttpResult(
                code,
                text
            )

        } finally {

            connection.disconnect()
        }
    }

    // =========================================================
    // WAIT ICE
    // =========================================================

    private fun waitForIceGathering(
        timeoutMs: Long = 10000L,
        callback: () -> Unit
    ) {

        thread {

            val start =
                System.currentTimeMillis()

            while (
                System.currentTimeMillis() -
                    start <
                    timeoutMs
            ) {

                val state =
                    peerConnection
                        ?.iceGatheringState()

                if (
                    state ==
                    PeerConnection.IceGatheringState.COMPLETE
                ) {
                    break
                }

                Thread.sleep(50)
            }

            callback()
        }
    }

    // =========================================================
    // WEBRTC THREAD HELPER
    // =========================================================

    private fun runOnPeerThread(
        block: () -> Unit
    ) {

        thread {
            try {
                block()
            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error pada PeerConnection thread",
                    e
                )
            }
        }
    }
}
