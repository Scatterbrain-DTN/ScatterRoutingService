package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import java.net.InetAddress

fun wifiDirectInfo(wifiP2pInfo: WifiP2pInfo): WifiDirectInfo {
    return WifiDirectInfoImpl(wifiP2pInfo)
}

class WifiDirectInfoImpl(private val wifiP2pInfo: WifiP2pInfo): WifiDirectInfo {
    override fun groupFormed(): Boolean {
        return wifiP2pInfo.groupFormed
    }

    override fun groupOwnerAddress(): InetAddress? {
        return wifiP2pInfo.groupOwnerAddress
    }

    override fun isGroupOwner(): Boolean {
        return wifiP2pInfo.isGroupOwner
    }

    override fun p2pInfo(): WifiP2pInfo {
        return wifiP2pInfo
    }
}