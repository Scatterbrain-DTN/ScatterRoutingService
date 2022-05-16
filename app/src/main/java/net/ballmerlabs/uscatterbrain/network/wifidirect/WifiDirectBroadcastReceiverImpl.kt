package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver.P2pState
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.*
import androidx.core.app.ActivityCompat
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
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
        private val channel: Channel,
        @Named(RoutingServiceComponent.NamedSchedulers.OPERATIONS) private val operationScheduler: Scheduler
) : BroadcastReceiver(), WifiDirectBroadcastReceiver {
    
    private val LOG by scatterLog()

    private val thisDeviceChangedSubject = BehaviorSubject.create<WifiP2pDevice>().toSerialized()
    private val connectionSubject = BehaviorSubject.create<WifiP2pInfo>().toSerialized()
    private val deviceListSubject = BehaviorSubject.create<WifiP2pDeviceList>().toSerialized()
    private val p2pStateSubject = BehaviorSubject.create<P2pState>().toSerialized()
    private val mListener = PeerListListener { value: WifiP2pDeviceList -> deviceListSubject.onNext(value) }
    private val mConnectionInfoListener = ConnectionInfoListener { value ->
        connectionSubject.onNext(value)
        LOG.v("retrieved WifiP2pInfo: ${value.groupFormed}")
    }

    private fun p2pStateChangedAction(intent: Intent) {
        LOG.v("WIFI_P2P_STATE_CHANGED_ACTION")
        // Determine if Wifi P2P mode is enabled
        val state = intent.getIntExtra(EXTRA_WIFI_STATE, -1)
        if (state == WIFI_P2P_STATE_ENABLED) {
            p2pStateSubject.onNext(P2pState.STATE_ENABLED)
        } else {
            p2pStateSubject.onNext(P2pState.STATE_DISABLED)
        }
    }

    private fun peersChangedAction(context: Context) {
        // The peer list has changed!
        LOG.v("WIFI_P2P_PEERS_CHANGED_ACTION")
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LOG.e("cannot request peers without ACCESS_FINE_LOCATION permission")
            manager.requestPeers(channel, mListener)
        }
    }

    private fun connectionChangedAction() {
        // Connection state changed!
        LOG.v("WIFI_P2P_CONNECTION_CHANGED_ACTION")
        manager.requestConnectionInfo(channel, mConnectionInfoListener)
    }

    private fun thisDeviceChangedAction(intent: Intent) {
        val device = intent.getParcelableExtra<WifiP2pDevice>(EXTRA_WIFI_P2P_DEVICE)
        if (device == null) {
            LOG.e("device was null")
        } else {
            LOG.v("WIFI_P2P_THIS_DEVICE_CHANGED_ACTION ${device.isGroupOwner}")
            thisDeviceChangedSubject.onNext(device)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when(val action = intent.action) {
            WIFI_P2P_STATE_CHANGED_ACTION -> p2pStateChangedAction(intent)
            WIFI_P2P_PEERS_CHANGED_ACTION -> peersChangedAction(context)
            WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionChangedAction()
            WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> thisDeviceChangedAction(intent)
            else -> LOG.v("unhandled wifi p2p action $action")
        }
    }

    override fun observeP2pState(): Observable<P2pState> {
        return p2pStateSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceChangedSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observeConnectionInfo(): Observable<WifiDirectInfo> {
        return connectionSubject.
                map { i -> wifiDirectInfo(i) }
                .delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return deviceListSubject.delay(0, TimeUnit.SECONDS, operationScheduler)
    }

    override fun asReceiver(): BroadcastReceiver {
        return this
    }
}