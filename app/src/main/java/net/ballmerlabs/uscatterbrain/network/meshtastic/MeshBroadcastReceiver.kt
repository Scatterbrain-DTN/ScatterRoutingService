package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.Intent

interface MeshBroadcastReceiver {
    companion object {
        fun actionReceived(portNum: Int) = "$prefix.RECEIVED.$portNum"
    }

    fun handle(intent: Intent)
}