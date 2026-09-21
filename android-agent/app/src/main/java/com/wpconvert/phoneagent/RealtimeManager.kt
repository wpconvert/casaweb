package com.wpconvert.phoneagent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
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
    }

    // =========================
    // WEBRTC
    // =========================

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

    // =========================
    // STATE
    // =========================

    private var initialized =
        false

    private var capturing =
        false

    private var currentSessionId:
        String? = null

    // =========================
    // INITIALIZE WEBRTC
    // =========================

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

    // =========================
    // START SCREEN CAPTURE
    // =========================

    /*
     * PENTING:
     *
     * Fungsi ini TIDAK boleh dipanggil
     * langsung dari MainActivity.
     *
     * Fungsi ini dipanggil oleh
     * ScreenCaptureService setelah:
     *
     * startForeground(
     *     ...,
     *     FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     * )
     *
     * Dengan begitu Android sudah mengetahui
     * bahwa aplikasi sedang menjalankan
     * foreground service untuk MediaProjection.
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

            // =========================
            // EGL / TEXTURE HELPER
            // =========================

            val eglBase =
                org.webrtc.EglBase.create()

            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    "ScreenCaptureThread",
                    eglBase.eglBaseContext
                )

            // =========================
            // VIDEO SOURCE
            // =========================

            videoSource =
                factory.createVideoSource(
                    false
                )

            // =========================
            // SCREEN CAPTURER
            // =========================

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

            // =========================
            // INITIALIZE CAPTURER
            // =========================

            capturer.initialize(
                helper,
                context.applicationContext,
                source.capturerObserver
            )

            // =========================
            // START CAPTURE
            // =========================

            /*
             * Resolusi target.
             *
             * WebRTC akan menangani scaling
             * sesuai kemampuan device.
             */

            capturer.startCapture(
                720,
                1600,
                30
            )

            // =========================
            // VIDEO TRACK
            // =========================

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

    // =========================
    // PEER CONNECTION
    // =========================

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
                PeerConnection.SdpSemantics
                    .UNIFIED_PLAN

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
                                org.webrtc.DataChannel
                        ) {

                            Log.d(
                                TAG,
                                "DataChannel diterima"
                            )
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

            // =========================
            // ADD SCREEN TRACK
            // =========================

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

    // =========================
    // CREATE OFFER
    // =========================

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

                                callback(
                                    description
                                )
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

    // =========================
    // PUBLISH CLOUDFLARE
    // =========================

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
                        }

                    Log.d(
                        TAG,
                        "Mengirim SDP ke Worker"
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
                        "Cloudflare response: $responseCode"
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

                    val answerSdp =
                        json.optString(
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

                    val answer =
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            answerSdp
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

    // =========================
    // SET REMOTE ANSWER
    // =========================

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

    // =========================
    // ICE
    // =========================

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

    // =========================
    // GET VIDEO TRACK
    // =========================

    fun getVideoTrack():
        VideoTrack? {

        return videoTrack
    }

    // =========================
    // CAPTURE STATUS
    // =========================

    fun isCapturing():
        Boolean {

        return capturing
    }

    // =========================
    // STOP SCREEN CAPTURE
    // =========================

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

    // =========================
    // DISPOSE
    // =========================

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

        currentSessionId =
            null

        Log.d(
            TAG,
            "RealtimeManager selesai dispose"
        )
    }
}
