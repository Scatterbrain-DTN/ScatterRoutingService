package net.ballmerlabs.uscatterbrain.network.wifidirect

import java.net.InetAddress


data class WifiDirectInfo(
    val groupFormed: Boolean,
    val groupOwnerAddress: InetAddress?,
    val isGroupOwner: Boolean,
) {
    companion object {

    }
}