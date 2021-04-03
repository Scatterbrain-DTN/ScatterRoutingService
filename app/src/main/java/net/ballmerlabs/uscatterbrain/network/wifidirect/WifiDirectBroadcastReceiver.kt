package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import io.reactivex.Observable

/**
 * dagger2 interface for WifiDirectBroadcastReceiver
 */
interface WifiDirectBroadcastReceiver {
    fun observeP2pState(): Observable<WifiDirectBroadcastReceiverImpl.P2pState>

    fun observeThisDevice(): Observable<WifiP2pDevice>

    fun observeConnectionInfo(): Observable<WifiP2pInfo>

    fun observePeers(): Observable<WifiP2pDeviceList>

    fun asReceiver(): BroadcastReceiver
}