package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import io.reactivex.Observable

/**
 * dagger2 interface for WifiDirectBroadcastReceiver
 */
interface WifiDirectBroadcastReceiver {

    enum class P2pState {
        STATE_DISABLED, STATE_ENABLED
    }

    /**
     * observe wifi p2p state
     * @return Observable emitting P2pState objects
     */
    fun observeP2pState(): Observable<P2pState>

    /**
     * @return Observable emitting WifiP2pDevice
     */
    fun observeThisDevice(): Observable<WifiP2pDevice>

    /**
     * @return Observable emitting WifiP2pInfo
     */
    fun observeConnectionInfo(): Observable<WifiDirectInfo>

    /**
     * Observe connected devices
     * @return Observable emitting WifiP2pDeviceList
     */
    fun observePeers(): Observable<WifiP2pDeviceList>

    /**
     * Perform an explicit cast from this object to a regular BroadcastReceiver
     */
    fun asReceiver(): BroadcastReceiver
}