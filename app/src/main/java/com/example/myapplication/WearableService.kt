package com.example.myapplication

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONException
import org.json.JSONObject

class WearableService(mainActivity: MainActivity) : WearableListenerService(), MessageClient.OnMessageReceivedListener {

    private val context: Context = mainActivity

    override fun onCreate() {
        super.onCreate()

        Log.i("service", "oncreate")
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(p0: MessageEvent) {
        if (p0.path == "/data") {
            val data = String(p0.data)

            try {
                val json = JSONObject(data)

                Log.i("res", json.toString())

            } catch (e: JSONException) {
                Log.i("json", "jsonException")
                e.printStackTrace()
            }
        }
    }

    fun sendStart() {
        sendMessageToWatch("/start")
    }

    fun sendStop() {
        sendMessageToWatch("/stop")
    }

    private fun sendMessageToWatch(path: String) {

        Wearable.getNodeClient(context)
            .connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    val sendMessageTask = Wearable.getMessageClient(context)
                        .sendMessage(node.id, path, " ".toByteArray())

                    sendMessageTask.addOnSuccessListener {
                        Log.i("send", "sssssuccess $path")
                    }.addOnFailureListener {
                        Log.i("send", "ssssfail")
                    }
                }
            }
            .addOnFailureListener {
                Log.i("node", "fail")
            }
    }
}