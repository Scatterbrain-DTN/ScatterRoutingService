package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.net.wifi.p2p.WifiP2pInfo
import java.net.InetAddress

class MockWIfiDirectInfo(
        private val groupFormed: Boolean,
        private val groupOwnerAddress: InetAddress,
        private val isGroupOwner: Boolean
): WifiDirectInfo {
    override fun groupFormed(): Boolean {
        return groupFormed
    }

    override fun groupOwnerAddress(): InetAddress {
        return groupOwnerAddress
    }

    override fun isGroupOwner(): Boolean {
        return isGroupOwner
    }

    override fun p2pInfo(): WifiP2pInfo {
        return WifiP2pInfo()
    }
}