package com.wpconvert.phoneagent

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI


class WebSocketClientManager(
    private val context: Context
) {

    private var socket: WebSocketClient? = null


    fun connect() {

        // GANTI IP INI DENGAN IP PC YANG MENJALANKAN server.py
        val serverUrl = "ws://192.168.1.10:8765"


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


            }


            override fun onMessage(message: String?) {

                // nanti untuk menerima perintah dari server
                // contoh:
                // klik layar
                // setting
                // screenshot


            }


            override fun onClose(
                code: Int,
                reason: String?,
                remote: Boolean
            ) {

            }


            override fun onError(ex: Exception?) {

                ex?.printStackTrace()

            }

        }


        socket?.connect()

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
