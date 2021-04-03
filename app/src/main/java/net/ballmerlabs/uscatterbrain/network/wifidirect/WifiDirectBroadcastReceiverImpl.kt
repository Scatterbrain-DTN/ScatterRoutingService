package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.net.wifi.p2p.WifiP2pManager.PeerListListener
import android.util.Log
import androidx.core.app.ActivityCompat
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * BroadcastReceiver for wifi direct related broadcasts.
 *
 * this class converts broadcasts into observables
 */
@Singleton
class WifiDirectBroadcastReceiverImpl @Inject constructor(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_OPERATIONS) private val operationScheduler: Scheduler
) : BroadcastReceiver(), WifiDirectBroadcastReceiver {
    enum class P2pState {
        STATE_DISABLED, STATE_ENABLED
    }

    private val thisDeviceChangedSubject = BehaviorSubject.create<WifiP2pDevice>().toSerialized()
    private val connectionSubject = BehaviorSubject.create<WifiP2pInfo>().toSerialized()
    private val deviceListSubject = BehaviorSubject.create<WifiP2pDeviceList>().toSerialized()
    private val p2pStateSubject = BehaviorSubject.create<P2pState>().toSerialized()
    private val mListener = PeerListListener { value: WifiP2pDeviceList -> deviceListSubject.onNext(value) }
    private val mConnectionInfoListener = ConnectionInfoListener { value: WifiP2pInfo ->
        connectionSubject.onNext(value)
        Log.v(TAG, "retrieved WifiP2pInfo: $value")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION == action) {
            Log.v(TAG, "WIFI_P2P_STATE_CHANGED_ACTION")
            // Determine if Wifi P2P mode is enabled
            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateSubject.onNext(P2pState.STATE_ENABLED)
            } else {
                p2pStateSubject.onNext(P2pState.STATE_DISABLED)
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION == action) {
            // The peer list has changed!
            Log.v(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION")
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "cannot request peers without ACCESS_FINE_LOCATION permission")
                        manager.requestPeers(channel, mListener)
            }
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION == action) {
            // Connection state changed!
            Log.v(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION")
            val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
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
                thisDeviceChangedSubject.onNext(device)
            }
        }
    }

    override fun observeP2pState(): Observable<P2pState> {
        return p2pStateSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceChangedSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observeConnectionInfo(): Observable<WifiP2pInfo> {
        return connectionSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return deviceListSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun asReceiver(): BroadcastReceiver {
        return this
    }

    companion object {
        private const val TAG = "WifiDirectBroadcastReceiver"
    }

}