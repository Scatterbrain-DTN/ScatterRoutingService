package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver.P2pState

class MockWifiDirectBroadcastReceiver(private val broadcastReceiver: BroadcastReceiver): WifiDirectBroadcastReceiver {

    val p2pStateRelay = BehaviorRelay.create<P2pState>()
    val thisDeviceRelay = BehaviorRelay.create<WifiP2pDevice>()
    val connectionInfoRelay = BehaviorRelay.create<WifiDirectInfo>()
    val p2pDeviceListRelay = BehaviorRelay.create<WifiP2pDeviceList>()

    override fun observeP2pState(): Observable<P2pState> {
        return p2pStateRelay
    }

    override fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceRelay
    }

    override fun observeConnectionInfo(): Observable<WifiDirectInfo> {
        return connectionInfoRelay
    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return p2pDeviceListRelay
    }

    override fun asReceiver(): BroadcastReceiver {
        return broadcastReceiver
    }
}