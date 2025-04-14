package net.ballmerlabs.uscatterbrain.mock.network.wifidirect

import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import net.ballmerlabs.uscatterbrain.WifiDirectProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeWifiDirectProvider @Inject constructor(
    private val channel: Channel,
    private val manager: WifiP2pManager
) : WifiDirectProvider {
    override fun getChannel(): WifiP2pManager.Channel? {
        return channel
    }

    override fun getManager(): WifiP2pManager? {
        return manager
    }
}