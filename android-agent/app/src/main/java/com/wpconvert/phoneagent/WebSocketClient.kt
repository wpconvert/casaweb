package com.wpconvert.phoneagent

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import kotlin.concurrent.thread


class WebSocketClientManager(
    private val context: Context
) {

    private var socket: WebSocketClient? = null


    fun connect() {

        if (socket?.isOpen == true) {
            return
        }


        // Cloudflare WebSocket server
        val serverUrl =
            "wss://web-phone-oneforall.danip4848.workers.dev/ws"


        println(
            "WebSocket: mencoba koneksi ke $serverUrl"
        )


        socket = object : WebSocketClient(
            URI(serverUrl)
        ) {


            override fun onOpen(
                handshake: ServerHandshake?
            ) {

                println(
                    "WebSocket connected"
                )


                val data =
                    JSONObject()


                data.put(
                    "type",
                    "register"
                )


                data.put(
                    "id",
                    getDeviceId()
                )


                data.put(
                    "name",
                    "Web Phone Agent"
                )


                data.put(
                    "model",
                    Build.MODEL
                )


                println(
                    "Mengirim register: $data"
                )


                send(
                    data.toString()
                )


                println(
                    "Register terkirim"
                )

            }


            override fun onMessage(
                message: String?
            ) {

                println(
                    "Server: $message"
                )

            }


            override fun onClose(
                code: Int,
                reason: String?,
                remote: Boolean
            ) {

                println(
                    "WebSocket closed"
                )

                println(
                    "Close code: $code"
                )

                println(
                    "Close reason: $reason"
                )

                println(
                    "Remote: $remote"
                )

            }


            override fun onError(
                ex: Exception?
            ) {

                println(
                    "WebSocket ERROR"
                )

                ex?.printStackTrace()

            }

        }


        thread {

            try {

                println(
                    "WebSocket thread: connect()"
                )

                socket?.connect()

            } catch (e: Exception) {

                println(
                    "WebSocket connect exception"
                )

                e.printStackTrace()

            }

        }

    }


    private fun getDeviceId(): String {

        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

    }


    fun disconnect() {

        println(
            "WebSocket disconnect()"
        )

        socket?.close()

    }

}
