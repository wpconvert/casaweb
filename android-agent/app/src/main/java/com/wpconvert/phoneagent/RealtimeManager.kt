package com.wpconvert.phoneagent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
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
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RealtimeManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "RealtimeManager"
        private const val WORKER =
            "https://web-phone-oneforall.danip4848.workers.dev"

        private const val CONTROL = "controls"
        private const val TRANSPORT = "server-events"
    }

    // =========================================================
    // WEBRTC
    // =========================================================

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null

    private var initialized = false
    private var capturing = false

    private var sessionId: String? = null

    // =========================================================
    // CONTROL
    // =========================================================

    private var controlChannel: DataChannel? = null
    private var controlChannelId: Int? = null

    @Volatile
    private var controlReady = false

    // =========================================================
    // INITIALIZE
    // =========================================================

    fun initialize() {
        if (initialized) return

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val egl = org.webrtc.EglBase.create()

            val encoder =
                org.webrtc.DefaultVideoEncoderFactory(
                    egl.eglBaseContext,
                    true,
                    true
                )

            val decoder =
                org.webrtc.DefaultVideoDecoderFactory(
                    egl.eglBaseContext
                )

            factory =
                PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoder)
                    .setVideoDecoderFactory(decoder)
                    .createPeerConnectionFactory()

            initialized = factory != null

            Log.d(TAG, "WebRTC initialized=$initialized")

        } catch (e: Exception) {
            Log.e(TAG, "WebRTC initialize gagal", e)
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
        if (!initialized) initialize()
        if (capturing) return

        val f = factory ?: return

        try {
            val egl = org.webrtc.EglBase.create()

            textureHelper =
                SurfaceTextureHelper.create(
                    "ScreenCapture",
                    egl.eglBaseContext
                )

            videoSource =
                f.createVideoSource(true)

            capturer =
                ScreenCapturerAndroid(
                    projectionData,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                            capturing = false
                        }
                    }
                )

            val source = videoSource ?: return
            val cap = capturer ?: return
            val helper = textureHelper ?: return

            cap.initialize(
                helper,
                context.applicationContext,
                object : org.webrtc.CapturerObserver {

                    private var frames = 0L

                    override fun onCapturerStarted(success: Boolean) {
                        Log.d(
                            TAG,
                            "Capturer started=$success"
                        )
                    }

                    override fun onCapturerStopped() {
                        Log.d(TAG, "Capturer stopped")
                    }

                    override fun onFrameCaptured(
                        frame: org.webrtc.VideoFrame
                    ) {
                        frames++

                        if (frames == 1L) {
                            Log.d(
                                TAG,
                                "First frame ${frame.buffer.width}x${frame.buffer.height}"
                            )
                        }

                        source.capturerObserver
                            .onFrameCaptured(frame)
                    }
                }
            )

            source.adaptOutputFormat(
                720,
                1600,
                30
            )

            cap.startCapture(
                720,
                1600,
                30
            )

            videoTrack =
                f.createVideoTrack(
                    "screen-track",
                    source
                )

            videoTrack?.setEnabled(true)

            capturing = true

            Log.d(
                TAG,
                "Screen capture aktif 720x1600@30"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Screen capture gagal", e)
            stopScreenCapture()
        }
    }

    // =========================================================
    // PEER CONNECTION
    // =========================================================

    fun createPeerConnection() {
        if (!initialized) initialize()
        if (pc != null) return

        val f = factory ?: return

        try {
            val ice =
                listOf(
                    PeerConnection.IceServer
                        .builder(
                            "stun:stun.cloudflare.com:3478"
                        )
                        .createIceServer()
                )

            val config =
                PeerConnection.RTCConfiguration(ice).apply {
                    sdpSemantics =
                        PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

            pc =
                f.createPeerConnection(
                    config,
                    object : PeerConnection.Observer {

                        override fun onSignalingChange(
                            state: PeerConnection.SignalingState
                        ) {
                            Log.d(TAG, "Signaling=$state")
                        }

                        override fun onIceConnectionChange(
                            state: PeerConnection.IceConnectionState
                        ) {
                            Log.d(TAG, "ICE=$state")
                        }

                        override fun onIceConnectionReceivingChange(
                            receiving: Boolean
                        ) {
                        }

                        override fun onIceGatheringChange(
                            state: PeerConnection.IceGatheringState
                        ) {
                            Log.d(TAG, "ICE gathering=$state")
                        }

                        override fun onIceCandidate(
                            candidate: IceCandidate
                        ) {
                            Log.d(TAG, "ICE candidate")
                        }

                        override fun onIceCandidatesRemoved(
                            candidates: Array<out IceCandidate>
                        ) {
                        }

                        override fun onAddStream(
                            stream: org.webrtc.MediaStream
                        ) {
                        }

                        override fun onRemoveStream(
                            stream: org.webrtc.MediaStream
                        ) {
                        }

                        override fun onDataChannel(
                            channel: DataChannel
                        ) {
                            Log.d(
                                TAG,
                                "DataChannel received=${channel.label()}"
                            )

                            if (channel.label() == CONTROL) {
                                attachControl(channel)
                            }
                        }

                        override fun onRenegotiationNeeded() {
                            Log.d(TAG, "Renegotiation needed")
                        }

                        override fun onAddTrack(
                            receiver: org.webrtc.RtpReceiver,
                            mediaStreams:
                                Array<out org.webrtc.MediaStream>
                        ) {
                        }

                        override fun onTrack(
                            transceiver: org.webrtc.RtpTransceiver
                        ) {
                        }

                        override fun onIceCandidateError(
                            event:
                                org.webrtc.IceCandidateErrorEvent
                        ) {
                            Log.e(
                                TAG,
                                "ICE error=${event.errorText}"
                            )
                        }

                        override fun onSelectedCandidatePairChanged(
                            event:
                                org.webrtc.CandidatePairChangeEvent
                        ) {
                        }

                        override fun onConnectionChange(
                            state:
                                PeerConnection.PeerConnectionState
                        ) {
                            Log.d(TAG, "PC=$state")
                        }
                    }
                )

            videoTrack?.let {
                pc?.addTrack(
                    it,
                    listOf("screen-stream")
                )
            }

            Log.d(TAG, "PeerConnection ready")

        } catch (e: Exception) {
            Log.e(TAG, "PeerConnection gagal", e)
        }
    }

    // =========================================================
    // CREATE OFFER
    // =========================================================

    fun createOffer(
        callback: (SessionDescription?) -> Unit
    ) {
        val connection = pc ?: run {
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
                    connection.setLocalDescription(
                        object : org.webrtc.SdpObserver {

                            override fun onSetSuccess() {
                                waitIce(connection)
                                callback(
                                    connection.localDescription
                                )
                            }

                            override fun onCreateSuccess(
                                description: SessionDescription
                            ) {
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {
                                callback(null)
                            }

                            override fun onSetFailure(
                                error: String
                            ) {
                                Log.e(
                                    TAG,
                                    "setLocal failed=$error"
                                )
                                callback(null)
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(
                    error: String
                ) {
                    Log.e(
                        TAG,
                        "createOffer failed=$error"
                    )
                    callback(null)
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            },
            constraints
        )
    }

    // =========================================================
    // CLOUDFLARE SESSION
    // =========================================================

    fun createCloudflareSession(
        callback:
            (
                Boolean,
                String?,
                String?
            ) -> Unit
    ) {
        thread {
            try {
                val result =
                    request(
                        "POST",
                        "$WORKER/api/session",
                        "{}"
                    )

                val json =
                    JSONObject(result)

                val id =
                    json.optString(
                        "sessionId",
                        ""
                    )

                if (id.isBlank()) {
                    callback(
                        false,
                        null,
                        result
                    )
                    return@thread
                }

                sessionId = id

                Log.d(
                    TAG,
                    "Session=$id"
                )

                callback(
                    true,
                    id,
                    null
                )

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Create session gagal",
                    e
                )

                callback(
                    false,
                    null,
                    e.message
                )
            }
        }
    }

    // =========================================================
    // PUBLISH VIDEO
    // =========================================================

    fun publishToCloudflare(
        session: String,
        deviceId: String,
        callback:
            (
                Boolean,
                SessionDescription?,
                String?
            ) -> Unit
    ) {
        val connection = pc ?: run {
            callback(
                false,
                null,
                "PeerConnection belum ada"
            )
            return
        }

        sessionId = session

        createOffer { offer ->

            if (offer == null) {
                callback(
                    false,
                    null,
                    "Offer gagal"
                )
                return@createOffer
            }

            thread {
                try {
                    val body =
                        JSONObject().apply {
                            put(
                                "sessionId",
                                session
                            )
                            put(
                                "deviceId",
                                deviceId
                            )
                            put(
                                "sdp",
                                offer.description
                            )
                            put("mid", "0")
                            put(
                                "trackName",
                                "screen-$deviceId"
                            )
                        }

                    val response =
                        request(
                            "POST",
                            "$WORKER/api/publish",
                            body.toString()
                        )

                    val json =
                        JSONObject(response)

                    val cf =
                        json.optJSONObject(
                            "cloudflare"
                        )

                    val desc =
                        cf?.optJSONObject(
                            "sessionDescription"
                        )

                    val sdp =
                        desc?.optString(
                            "sdp",
                            ""
                        )

                    if (sdp.isNullOrBlank()) {
                        callback(
                            false,
                            null,
                            response
                        )
                        return@thread
                    }

                    val answer =
                        SessionDescription(
                            SessionDescription.Type.ANSWER,
                            sdp
                        )

                    connection.setRemoteDescription(
                        object : org.webrtc.SdpObserver {

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "Video publish connected"
                                )

                                callback(
                                    true,
                                    answer,
                                    null
                                )

                                // Kontrol disiapkan setelah
                                // video sudah aman.
                                setupControlTransport()
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

                            override fun onCreateSuccess(
                                description: SessionDescription
                            ) {
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {
                            }
                        },
                        answer
                    )

                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Publish gagal",
                        e
                    )

                    callback(
                        false,
                        null,
                        e.message
                    )
                }
            }
        }
    }

    // =========================================================
    // CONTROL TRANSPORT
    // =========================================================

    private fun setupControlTransport() {
        val sid = sessionId ?: return
        val connection = pc ?: return

        thread {
            try {
                Log.d(
                    TAG,
                    "Menyiapkan DataChannel transport..."
                )

                // -------------------------------------------------
                // 1. ESTABLISH TRANSPORT
                // -------------------------------------------------

                val establishBody =
                    JSONObject().apply {
                        put(
                            "sessionId",
                            sid
                        )
                        put(
                            "location",
                            "remote"
                        )
                        put(
                            "dataChannelName",
                            TRANSPORT
                        )
                    }

                val establishResponse =
                    request(
                        "POST",
                        "$WORKER/api/datachannel-establish",
                        establishBody.toString()
                    )

                val establishJson =
                    JSONObject(
                        establishResponse
                    )

                val transport =
                    establishJson
                        .optJSONObject(
                            "sessionDescription"
                        )
                        ?: establishJson
                            .optJSONObject(
                                "cloudflare"
                            )
                            ?.optJSONObject(
                                "sessionDescription"
                            )

                val transportSdp =
                    transport?.optString(
                        "sdp",
                        ""
                    )

                if (
                    transport == null ||
                    transportSdp.isNullOrBlank()
                ) {
                    throw Exception(
                        "DataChannel establish SDP kosong: $establishResponse"
                    )
                }

                val transportType =
                    transport.optString(
                        "type",
                        "offer"
                    )

                val transportOffer =
                    SessionDescription(
                        if (
                            transportType.equals(
                                "answer",
                                true
                            )
                        ) {
                            SessionDescription.Type.ANSWER
                        } else {
                            SessionDescription.Type.OFFER
                        },
                        transportSdp
                    )

                // -------------------------------------------------
                // 2. APPLY CLOUDFLARE OFFER
                // -------------------------------------------------

                val remoteOk =
                    setRemoteDescriptionSync(
                        connection,
                        transportOffer
                    )

                if (!remoteOk) {
                    throw Exception(
                        "Gagal set DataChannel remote SDP"
                    )
                }

                // -------------------------------------------------
                // 3. CREATE ANSWER
                // -------------------------------------------------

                val answer =
                    createAnswerSync(
                        connection
                    )

                if (answer == null) {
                    throw Exception(
                        "Gagal membuat DataChannel answer"
                    )
                }

                waitIce(connection)

                val answerSdp =
                    connection.localDescription
                        ?.description
                        ?: answer.description

                // -------------------------------------------------
                // 4. RENEGOTIATE
                // -------------------------------------------------

                val renegotiateBody =
                    JSONObject().apply {
                        put(
                            "sessionId",
                            sid
                        )
                        put(
                            "sdp",
                            answerSdp
                        )
                    }

                request(
                    "PUT",
                    "$WORKER/api/renegotiate",
                    renegotiateBody.toString()
                )

                Log.d(
                    TAG,
                    "DataChannel transport renegotiated"
                )

                // -------------------------------------------------
                // 5. CREATE APPLICATION CHANNEL ON PUBLISHER
                // -------------------------------------------------

                createControlChannel()

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Setup DataChannel gagal",
                    e
                )
            }
        }
    }

    // =========================================================
    // CREATE CONTROL CHANNEL
    // =========================================================

    private fun createControlChannel() {
        val sid = sessionId ?: return
        val connection = pc ?: return

        try {
            val body =
                JSONObject().apply {
                    put(
                        "sessionId",
                        sid
                    )
                    put(
                        "dataChannelName",
                        CONTROL
                    )
                    put(
                        "location",
                        "local"
                    )
                }

            val response =
                request(
                    "POST",
                    "$WORKER/api/datachannel-publish",
                    body.toString()
                )

            val json =
                JSONObject(response)

            val channels =
                json.optJSONArray(
                    "dataChannels"
                )

            if (
                channels == null ||
                channels.length() == 0
            ) {
                throw Exception(
                    "Cloudflare tidak mengembalikan dataChannels: $response"
                )
            }

            val channelJson =
                channels.getJSONObject(0)

            val id =
                channelJson.optInt(
                    "id",
                    -1
                )

            if (id < 0) {
                throw Exception(
                    "Channel ID tidak valid"
                )
            }

            createNegotiatedControlChannel(
                connection,
                id
            )

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Create control channel gagal",
                e
            )
        }
    }

    private fun createNegotiatedControlChannel(
        connection: PeerConnection,
        id: Int
    ) {
        try {
            controlChannel?.unregisterObserver()
            controlChannel?.dispose()

            controlChannel = null
            controlReady = false
            controlChannelId = id

            val init =
                DataChannel.Init().apply {
                    ordered = true
                    negotiated = true
                    this.id = id
                }

            val channel =
                connection.createDataChannel(
                    CONTROL,
                    init
                )
                    ?: throw Exception(
                        "createDataChannel returned null"
                    )

            attachControl(channel)

            Log.d(
                TAG,
                "CONTROL channel created id=$id"
            )

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Control channel gagal",
                e
            )
        }
    }

    private fun attachControl(
        channel: DataChannel
    ) {
        controlChannel = channel
        controlReady =
            channel.state() == DataChannel.State.OPEN

        channel.registerObserver(
            object : DataChannel.Observer {

                override fun onStateChange() {
                    val state =
                        channel.state()

                    controlReady =
                        state ==
                            DataChannel.State.OPEN

                    Log.d(
                        TAG,
                        "CONTROL state=$state"
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

        val success =
            RemoteAccessibilityService
                .executeCommand(
                    command
                )

        Log.d(
            TAG,
            "CONTROL executed=$success"
        )

    } catch (e: Exception) {

        Log.e(
            TAG,
            "CONTROL message error",
            e
        )
    }
}
                    try {
                        val bytes =
                            ByteArray(
                                buffer.data.remaining()
                            )

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

                        // Publisher Android tidak perlu
                        // mengeksekusi command di sini.
                        // Channel ini menerima/menjaga
                        // koneksi kontrol.
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "CONTROL message error",
                            e
                        )
                    }
                }

                override fun onBufferedAmountChange(
                    previousAmount: Long
                ) {
                }
            }
        )
    }

    // =========================================================
    // SEND CONTROL
    // =========================================================

    fun sendControlCommand(
        command: String
    ): Boolean {
        val channel =
            controlChannel
                ?: return false

        if (
            channel.state() !=
            DataChannel.State.OPEN
        ) {
            return false
        }

        return try {
            val data =
                command.toByteArray(
                    Charsets.UTF_8
                )

            channel.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(data),
                    false
                )
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Send control gagal",
                e
            )
            false
        }
    }

    fun isControlChannelReady(): Boolean =
        controlReady

    fun getControlChannelId(): Int? =
        controlChannelId

    // =========================================================
    // SDP HELPERS
    // =========================================================

    private fun setRemoteDescriptionSync(
        connection: PeerConnection,
        description: SessionDescription
    ): Boolean {
        val latch =
            CountDownLatch(1)

        var success = false

        connection.setRemoteDescription(
            object : org.webrtc.SdpObserver {

                override fun onSetSuccess() {
                    success = true
                    latch.countDown()
                }

                override fun onSetFailure(
                    error: String
                ) {
                    Log.e(
                        TAG,
                        "setRemote failure=$error"
                    )
                    latch.countDown()
                }

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {
                }

                override fun onCreateFailure(
                    error: String
                ) {
                    latch.countDown()
                }
            },
            description
        )

        latch.await(
            15,
            TimeUnit.SECONDS
        )

        return success
    }

    private fun createAnswerSync(
        connection: PeerConnection
    ): SessionDescription? {
        val latch =
            CountDownLatch(1)

        var result:
            SessionDescription? = null

        connection.createAnswer(
            object : org.webrtc.SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {
                    connection.setLocalDescription(
                        object : org.webrtc.SdpObserver {

                            override fun onSetSuccess() {
                                result =
                                    description
                                latch.countDown()
                            }

                            override fun onCreateSuccess(
                                description: SessionDescription
                            ) {
                            }

                            override fun onCreateFailure(
                                error: String
                            ) {
                                latch.countDown()
                            }

                            override fun onSetFailure(
                                error: String
                            ) {
                                Log.e(
                                    TAG,
                                    "set answer failed=$error"
                                )
                                latch.countDown()
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(
                    error: String
                ) {
                    Log.e(
                        TAG,
                        "create answer failed=$error"
                    )
                    latch.countDown()
                }

                override fun onSetSuccess() {
                }

                override fun onSetFailure(
                    error: String
                ) {
                }
            },
            MediaConstraints()
        )

        latch.await(
            15,
            TimeUnit.SECONDS
        )

        return result
    }

    private fun waitIce(
        connection: PeerConnection
    ) {
        if (
            connection.iceGatheringState() ==
            PeerConnection.IceGatheringState.COMPLETE
        ) {
            return
        }

        val latch =
            CountDownLatch(1)

        val observer =
            object : PeerConnection.Observer by EmptyPeerObserver() {

                override fun onIceGatheringChange(
                    state:
                        PeerConnection.IceGatheringState
                ) {
                    if (
                        state ==
                        PeerConnection.IceGatheringState.COMPLETE
                    ) {
                        latch.countDown()
                    }
                }
            }

        // Tidak mengganti observer aktif.
        // Tunggu berdasarkan SDP candidate timeout.
        Thread.sleep(1500)
    }

    // =========================================================
    // HTTP
    // =========================================================

    private fun request(
        method: String,
        urlString: String,
        body: String
    ): String {

        val connection =
            URL(urlString)
                .openConnection()
                    as HttpURLConnection

        try {
            connection.requestMethod =
                method

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

            connection.doInput = true

            if (
                method != "GET"
            ) {
                connection.doOutput = true

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

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val text =
                stream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "HTTP $code"

            if (
                code !in 200..299
            ) {
                throw Exception(
                    "HTTP $code: $text"
                )
            }

            return text

        } finally {
            connection.disconnect()
        }
    }

    // =========================================================
    // PUBLIC HELPERS
    // =========================================================

    fun setRemoteAnswer(
        answer: SessionDescription,
        callback: (() -> Unit)? = null
    ) {
        pc?.setRemoteDescription(
            object : org.webrtc.SdpObserver {

                override fun onSetSuccess() {
                    callback?.invoke()
                }

                override fun onSetFailure(
                    error: String
                ) {
                    Log.e(
                        TAG,
                        "Remote answer gagal=$error"
                    )
                }

                override fun onCreateSuccess(
                    description: SessionDescription
                ) {
                }

                override fun onCreateFailure(
                    error: String
                ) {
                }
            },
            answer
        )
    }

    fun addIceCandidate(
        candidate: IceCandidate
    ) {
        pc?.addIceCandidate(candidate)
    }

    fun getVideoTrack(): VideoTrack? =
        videoTrack

    fun isCapturing(): Boolean =
        capturing

    // =========================================================
    // STOP
    // =========================================================

    fun stopScreenCapture() {
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        try {
            capturer?.dispose()
        } catch (_: Exception) {
        }

        try {
            videoTrack?.dispose()
        } catch (_: Exception) {
        }

        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }

        try {
            textureHelper?.dispose()
        } catch (_: Exception) {
        }

        capturer = null
        videoTrack = null
        videoSource = null
        textureHelper = null

        capturing = false
    }

    // =========================================================
    // DISPOSE
    // =========================================================

    fun dispose() {
        stopScreenCapture()

        try {
            controlChannel?.unregisterObserver()
            controlChannel?.dispose()
        } catch (_: Exception) {
        }

        controlChannel = null
        controlChannelId = null
        controlReady = false

        try {
            pc?.close()
        } catch (_: Exception) {
        }

        pc = null

        try {
            factory?.dispose()
        } catch (_: Exception) {
        }

        factory = null
        sessionId = null
        initialized = false
    }

    // =========================================================
    // EMPTY OBSERVER
    // =========================================================

    private class EmptyPeerObserver :
        PeerConnection.Observer {

        override fun onSignalingChange(
            state: PeerConnection.SignalingState
        ) {}

        override fun onIceConnectionChange(
            state: PeerConnection.IceConnectionState
        ) {}

        override fun onIceConnectionReceivingChange(
            receiving: Boolean
        ) {}

        override fun onIceGatheringChange(
            state: PeerConnection.IceGatheringState
        ) {}

        override fun onIceCandidate(
            candidate: IceCandidate
        ) {}

        override fun onIceCandidatesRemoved(
            candidates: Array<out IceCandidate>
        ) {}

        override fun onAddStream(
            stream: org.webrtc.MediaStream
        ) {}

        override fun onRemoveStream(
            stream: org.webrtc.MediaStream
        ) {}

        override fun onDataChannel(
            dataChannel: DataChannel
        ) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver,
            mediaStreams:
                Array<out org.webrtc.MediaStream>
        ) {}

        override fun onTrack(
            transceiver: org.webrtc.RtpTransceiver
        ) {}

        override fun onIceCandidateError(
            event:
                org.webrtc.IceCandidateErrorEvent
        ) {}

        override fun onSelectedCandidatePairChanged(
            event:
                org.webrtc.CandidatePairChangeEvent
        ) {}

        override fun onConnectionChange(
            newState:
                PeerConnection.PeerConnectionState
        ) {}
    }
}
