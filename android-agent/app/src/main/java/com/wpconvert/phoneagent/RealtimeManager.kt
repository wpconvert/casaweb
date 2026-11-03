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
