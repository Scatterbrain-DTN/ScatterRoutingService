package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.BroadcastReceiver

abstract class MeshBroadcastReceiver: BroadcastReceiver() {
    companion object {
        fun actionReceived(portNum: Int) = "$prefix.RECEIVED.$portNum"
    }
}