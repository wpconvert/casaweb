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
