package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.BroadcastReceiver
import android.content.Intent

abstract class MeshBroadcastReceiver: BroadcastReceiver() {
    companion object {
        fun actionReceived(portNum: Int) = "$prefix.RECEIVED.$portNum"
    }
}