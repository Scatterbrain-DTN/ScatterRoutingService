package net.ballmerlabs.uscatterbrain.network.wifidirect

import java.net.InetAddress

interface WifiDirectInfo {
    fun groupFormed(): Boolean
    fun groupOwnerAddress(): InetAddress?
    fun isGroupOwner(): Boolean
}