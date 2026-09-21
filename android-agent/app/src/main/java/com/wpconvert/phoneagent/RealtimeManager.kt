package com.wpconvert.phoneagent

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class RealtimeManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "RealtimeManager"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null

    private var initialized = false
    private var capturing = false

    /**
     * Inisialisasi WebRTC.
     */
    fun initialize() {
        if (initialized) {
            Log.d(TAG, "WebRTC sudah diinisialisasi")
            return
        }

        Log.d(TAG, "Memulai inisialisasi WebRTC")

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val encoderFactory =
                org.webrtc.DefaultVideoEncoderFactory(
                    org.webrtc.EglBase.create().eglBaseContext,
                    true,
                    true
                )

            val decoderFactory =
                org.webrtc.DefaultVideoDecoderFactory(
                    org.webrtc.EglBase.create().eglBaseContext
                )

            peerConnectionFactory =
                PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()

            initialized = true

            Log.d(TAG, "WebRTC berhasil diinisialisasi")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal inisialisasi WebRTC", e)
        }
    }

    /**
     * Mulai capture layar Android.
     *
     * resultCode dan projectionData berasal dari
     * MediaProjection permission dialog Android.
     */
    fun startScreenCapture(
        resultCode: Int,
        projectionData: android.content.Intent
    ) {
        if (!initialized) {
            initialize()
        }

        if (capturing) {
            Log.d(TAG, "Screen capture sudah aktif")
            return
        }

        val factory = peerConnectionFactory

        if (factory == null) {
            Log.e(TAG, "PeerConnectionFactory belum tersedia")
            return
        }

        try {
            Log.d(TAG, "Menyiapkan screen capture")

            surfaceTextureHelper =
                SurfaceTextureHelper.create(
                    "ScreenCaptureThread",
                    org.webrtc.EglBase.create().eglBaseContext
                )

            videoSource = factory.createVideoSource(false)

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

                            try {
                                videoSource?.capturerStopped()
                            } catch (e: Exception) {
                                Log.e(
                                    TAG,
                                    "Gagal memberi tahu videoSource",
                                    e
                                )
                            }
                        }
                    }
                )

            val capturer = screenCapturer
            val helper = surfaceTextureHelper
            val source = videoSource

            if (capturer == null || helper == null || source == null) {
                Log.e(TAG, "Komponen screen capture tidak lengkap")
                return
            }

            capturer.initialize(
                helper,
                context,
                source.capturerObserver
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

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Gagal memulai screen capture",
                e
            )

            stopScreenCapture()
        }
    }

    /**
     * Membuat PeerConnection.
     */
    fun createPeerConnection() {

        if (!initialized) {
            initialize()
        }

        if (peerConnection != null) {
            Log.d(TAG, "PeerConnection sudah ada")
            return
        }

        val factory = peerConnectionFactory

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
                        .builder("stun:stun.cloudflare.com:3478")
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
                            dataChannel: org.webrtc.DataChannel
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
                            receiver: org.webrtc.RtpReceiver,
                            mediaStreams: Array<out org.webrtc.MediaStream>
                        ) {
                            Log.d(
                                TAG,
                                "Track diterima"
                            )
                        }

                        override fun onTrack(
                            transceiver: org.webrtc.RtpTransceiver
                        ) {
                            Log.d(
                                TAG,
                                "Transceiver track diterima"
                            )
                        }

                        override fun onIceCandidateError(
                            event: org.webrtc.IceCandidateErrorEvent
                        ) {
                            Log.e(
                                TAG,
                                "ICE candidate error: ${event.errorText}"
                            )
                        }

                        override fun onSelectedCandidatePairChanged(
                            event: org.webrtc.CandidatePairChangeEvent
                        ) {
                            Log.d(
                                TAG,
                                "Selected candidate pair berubah"
                            )
                        }

                        override fun onConnectionChange(
                            newState: PeerConnection.PeerConnectionState
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

    /**
     * Membuat SDP Offer.
     */
    fun createOffer(
        callback: (SessionDescription?) -> Unit
    ) {

        val connection = peerConnection

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
            object : org.webrtc.SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {

                    Log.d(
                        TAG,
                        "SDP Offer berhasil dibuat"
                    )

                    connection.setLocalDescription(
                        object : org.webrtc.SdpObserver {

                            override fun onCreateSuccess(
                                description: SessionDescription
                            ) {
                            }

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "Local SDP berhasil diset"
                                )

                                callback(description)
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

    /**
     * Set remote SDP answer.
     */
    fun setRemoteAnswer(
        answer: SessionDescription,
        callback: (() -> Unit)? = null
    ) {

        val connection = peerConnection

        if (connection == null) {
            Log.e(
                TAG,
                "PeerConnection belum dibuat"
            )
            return
        }

        connection.setRemoteDescription(
            object : org.webrtc.SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
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

    /**
     * Tambahkan ICE candidate dari server.
     */
    fun addIceCandidate(
        candidate: IceCandidate
    ) {

        val connection = peerConnection

        if (connection == null) {
            Log.e(
                TAG,
                "PeerConnection belum tersedia"
            )
            return
        }

        connection.addIceCandidate(candidate)

        Log.d(
            TAG,
            "ICE candidate ditambahkan"
        )
    }

    /**
     * Ambil video track.
     */
    fun getVideoTrack(): VideoTrack? {
        return videoTrack
    }

    /**
     * Apakah screen capture sedang aktif.
     */
    fun isCapturing(): Boolean {
        return capturing
    }

    /**
     * Hentikan screen capture.
     */
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

        screenCapturer?.dispose()
        screenCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        capturing = false

        Log.d(
            TAG,
            "Screen capture dihentikan"
        )
    }

    /**
     * Tutup PeerConnection dan semua resource WebRTC.
     */
    fun dispose() {

        Log.d(
            TAG,
            "Dispose RealtimeManager"
        )

        stopScreenCapture()

        peerConnection?.close()
        peerConnection = null

        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        initialized = false

        Log.d(
            TAG,
            "RealtimeManager selesai dispose"
        )
    }
}
