package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import java.net.InetAddress

interface WifiDirectInfo {
    fun groupFormed(): Boolean
    fun groupOwnerAddress(): InetAddress?
    fun isGroupOwner(): Boolean
    fun p2pInfo(): WifiP2pInfo
}