package com.wpconvert.phoneagent

import android.content.Context
import android.content.Intent
import android.util.Log
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack


class RealtimeManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "RealtimeManager"
    }


    private var peerConnectionFactory:
        PeerConnectionFactory? = null

    private var peerConnection:
        PeerConnection? = null

    private var surfaceTextureHelper:
        SurfaceTextureHelper? = null

    private var videoSource:
        VideoSource? = null

    private var videoTrack:
        VideoTrack? = null

    private var screenCapturer:
        ScreenCapturerAndroid? = null


    /*
    ==================================================
    INITIALIZE WEBRTC
    ==================================================
    */

    fun initialize() {

        Log.d(
            TAG,
            "Initializing WebRTC..."
        )


        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        )


        val encoderFactory =
            org.webrtc.DefaultVideoEncoderFactory(
                null,
                true,
                true
            )


        val decoderFactory =
            org.webrtc.DefaultVideoDecoderFactory(
                null
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


        Log.d(
            TAG,
            "WebRTC initialized"
        )
    }


    /*
    ==================================================
    START ANDROID SCREEN CAPTURE
    ==================================================
    */

    fun startScreenCapture(
        resultCode: Int,
        permissionData: Intent,
        width: Int,
        height: Int,
        density: Int
    ) {

        Log.d(
            TAG,
            "Starting WebRTC screen capture"
        )


        if (
            peerConnectionFactory == null
        ) {

            initialize()
        }


        stopScreenCapture()


        val factory =
            peerConnectionFactory
                ?: throw IllegalStateException(
                    "PeerConnectionFactory belum siap"
                )


        /*
        ==============================================
        SURFACE TEXTURE HELPER
        ==============================================
        */

        surfaceTextureHelper =
            SurfaceTextureHelper.create(
                "WebPhoneAgent-WebRTC",
                org.webrtc.EglBase
                    .create()
                    .eglBaseContext
            )


        /*
        ==============================================
        VIDEO SOURCE
        ==============================================
        */

        videoSource =
            factory.createVideoSource(
                false
            )


        /*
        ==============================================
        SCREEN CAPTURER
        ==============================================
        */

        screenCapturer =
            ScreenCapturerAndroid(
                permissionData,
                object :
                    org.webrtc.MediaProjection.Callback() {

                    override fun onStop() {

                        Log.d(
                            TAG,
                            "MediaProjection stopped"
                        )

                    }
                }
            )


        /*
        ==============================================
        INITIALIZE CAPTURER
        ==============================================
        */

        screenCapturer?.initialize(
            surfaceTextureHelper,
            context,
            videoSource?.capturerObserver
        )


        /*
        ==============================================
        START CAPTURE
        ==============================================
        */

        screenCapturer?.startCapture(
            width,
            height,
            30
        )


        /*
        ==============================================
        CREATE VIDEO TRACK
        ==============================================
        */

        videoTrack =
            factory.createVideoTrack(
                "screen-track",
                videoSource
            )


        videoTrack?.setEnabled(
            true
        )


        Log.d(
            TAG,
            "Screen VideoTrack created"
        )


        Log.d(
            TAG,
            "Screen capture started: " +
                "${width}x${height} @ 30fps"
        )
    }


    /*
    ==================================================
    GET VIDEO TRACK
    ==================================================
    */

    fun getVideoTrack():
        VideoTrack? {

        return videoTrack
    }


    /*
    ==================================================
    CREATE PEER CONNECTION
    ==================================================
    */

    fun createPeerConnection():
        PeerConnection? {

        val factory =
            peerConnectionFactory
                ?: run {

                    Log.e(
                        TAG,
                        "PeerConnectionFactory belum siap"
                    )

                    return null
                }


        val rtcConfig =
            PeerConnection.RTCConfiguration(
                emptyList()
            )


        rtcConfig.sdpSemantics =
            PeerConnection.SdpSemantics.UNIFIED_PLAN


        peerConnection =
            factory.createPeerConnection(
                rtcConfig,
                object :
                    PeerConnection.Observer {

                    override fun onSignalingChange(
                        state:
                            PeerConnection.SignalingState?
                    ) {

                        Log.d(
                            TAG,
                            "Signaling state: $state"
                        )
                    }


                    override fun onIceConnectionChange(
                        state:
                            PeerConnection.IceConnectionState?
                    ) {

                        Log.d(
                            TAG,
                            "ICE state: $state"
                        )
                    }


                    override fun onConnectionChange(
                        state:
                            PeerConnection.PeerConnectionState?
                    ) {

                        Log.d(
                            TAG,
                            "Connection state: $state"
                        )
                    }


                    override fun onIceGatheringChange(
                        state:
                            PeerConnection.IceGatheringState?
                    ) {

                        Log.d(
                            TAG,
                            "ICE gathering: $state"
                        )
                    }


                    override fun onIceCandidate(
                        candidate:
                            PeerConnection.IceCandidate?
                    ) {

                        Log.d(
                            TAG,
                            "ICE candidate received"
                        )
                    }


                    override fun onIceCandidatesRemoved(
                        candidates:
                            Array<out PeerConnection.IceCandidate>?
                    ) {
                    }


                    override fun onAddStream(
                        stream: MediaStream?
                    ) {
                    }


                    override fun onRemoveStream(
                        stream: MediaStream?
                    ) {
                    }


                    override fun onDataChannel(
                        dataChannel:
                            org.webrtc.DataChannel?
                    ) {
                    }


                    override fun onRenegotiationNeeded() {

                        Log.d(
                            TAG,
                            "Renegotiation needed"
                        )
                    }


                    override fun onAddTrack(
                        receiver:
                            org.webrtc.RtpReceiver?,
                        mediaStreams:
                            Array<out MediaStream>?
                    ) {
                    }


                    override fun onIceConnectionReceivingChange(
                        receiving: Boolean
                    ) {
                    }


                    override fun onStandardizedIceConnectionChange(
                        newState:
                            PeerConnection.IceConnectionState?
                    ) {
                    }


                    override fun onTrack(
                        transceiver:
                            org.webrtc.RtpTransceiver?
                    ) {
                    }


                    override fun onSelectedCandidatePairChanged(
                        event:
                            PeerConnection.CandidatePairChangeEvent?
                    ) {
                    }
                }
            )


        if (
            peerConnection == null
        ) {

            Log.e(
                TAG,
                "Gagal membuat PeerConnection"
            )

            return null
        }


        /*
        ==============================================
        ADD SCREEN TRACK
        ==============================================
        */

        videoTrack?.let { track ->

            peerConnection?.addTransceiver(
                track,
                PeerConnection.RtpTransceiver.RtpTransceiverInit(
                    PeerConnection.RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                )
            )

            Log.d(
                TAG,
                "Screen track added to PeerConnection"
            )
        }


        return peerConnection
    }


    /*
    ==================================================
    STOP SCREEN CAPTURE
    ==================================================
    */

    fun stopScreenCapture() {

        try {

            screenCapturer?.stopCapture()

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "stopCapture error",
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


        Log.d(
            TAG,
            "Screen capture stopped"
        )
    }


    /*
    ==================================================
    CLOSE WEBRTC
    ==================================================
    */

    fun close() {

        Log.d(
            TAG,
            "Closing WebRTC"
        )


        stopScreenCapture()


        peerConnection?.close()
        peerConnection = null


        peerConnectionFactory?.dispose()
        peerConnectionFactory = null


        Log.d(
            TAG,
            "WebRTC closed"
        )
    }
}
