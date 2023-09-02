package net.ballmerlabs.uscatterbrain

import android.net.wifi.p2p.WifiP2pManager

interface WifiDirectProvider {
    fun getManager(): WifiP2pManager?
    fun getChannel(): WifiP2pManager.Channel?
}