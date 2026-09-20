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


        // GANTI DENGAN IP PC SERVER
        val serverUrl = "wss://web-phone-oneforall.danip4848.workers.dev/ws"


        socket = object : WebSocketClient(
            URI(serverUrl)
        ) {


            override fun onOpen(handshake: ServerHandshake?) {

                val data = JSONObject()


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


                send(
                    data.toString()
                )


                println(
                    "WebSocket connected"
                )

            }



            override fun onMessage(message: String?) {

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
                    "WebSocket closed: $reason"
                )

            }



            override fun onError(ex: Exception?) {

                ex?.printStackTrace()

            }

        }



        thread {

            try {

                socket?.connect()

            } catch (e: Exception) {

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

        socket?.close()

    }

}
