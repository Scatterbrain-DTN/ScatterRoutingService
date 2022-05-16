package net.ballmerlabs.uscatterbrain.network.wifidirect

import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectInfo
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
}