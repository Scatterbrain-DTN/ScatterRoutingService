package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.os.Parcelable
import android.util.Log
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable

class WifiDirectBroadcastReceiver(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        context: Context
) : BroadcastReceiver() {
    enum class P2pState {
        STATE_DISABLED, STATE_ENABLED
    }

    private val thisDeviceChangedSubject = BehaviorRelay.create<WifiP2pDevice>()
    private val connectionSubject = BehaviorRelay.create<WifiP2pInfo>()
    private val deviceListSubject = BehaviorRelay.create<WifiP2pDeviceList>()
    private val p2pStateSubject = BehaviorRelay.create<P2pState>()
    private val mListener = PeerListListener { value: WifiP2pDeviceList -> deviceListSubject.accept(value) }
    private val mConnectionInfoListener = ConnectionInfoListener { value: WifiP2pInfo -> connectionSubject.accept(value) }
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION == action) {
            Log.v(TAG, "WIFI_P2P_STATE_CHANGED_ACTION")
            // Determine if Wifi P2P mode is enabled
            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateSubject.accept(P2pState.STATE_ENABLED)
            } else {
                p2pStateSubject.accept(P2pState.STATE_DISABLED)
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION == action) {
            // The peer list has changed!
            Log.v(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION")
            manager.requestPeers(channel, mListener)
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION == action) {
            // Connection state changed!
            Log.v(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION")
            val networkInfo = intent.getParcelableExtra<Parcelable>(WifiP2pManager.EXTRA_NETWORK_INFO) as NetworkInfo?
            if (networkInfo != null && networkInfo.isConnected) {
                manager.requestConnectionInfo(channel, mConnectionInfoListener)
            } else if (networkInfo != null) {
                //TODO: handle disconnections
            } else {
                Log.e(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION networkinfo was null")
            }
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION == action) {
            Log.v(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION")
            val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
            if (device == null) {
                Log.e(TAG, "device was null")
            } else {
                thisDeviceChangedSubject.accept(device)
            }
        }
    }

    fun observeP2pState(): Observable<P2pState> {
        return p2pStateSubject
    }

    fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceChangedSubject
    }

    fun observeConnectionInfo(): Observable<WifiP2pInfo> {
        return connectionSubject
    }

    fun observePeers(): Observable<WifiP2pDeviceList> {
        return deviceListSubject
    }

    fun asReceiver(): BroadcastReceiver {
        return this
    }

    companion object {
        private const val TAG = "WifiDirectBroadcastReceiver"
    }

}