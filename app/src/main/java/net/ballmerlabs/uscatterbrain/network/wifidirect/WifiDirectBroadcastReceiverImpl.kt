package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.*
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver.P2pState
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * BroadcastReceiver for wifi direct related broadcasts.
 *
 * this class converts broadcasts into observables
 */
@Singleton
class WifiDirectBroadcastReceiverImpl @Inject constructor() : BroadcastReceiver(), WifiDirectBroadcastReceiver {

    @Inject
    lateinit var manager: WifiP2pManager
    @Inject
    lateinit var channel: Channel

    private val LOG by scatterLog()

    private val thisDeviceChangedSubject = BehaviorSubject.create<WifiP2pDevice>()
    private val connectionSubject = BehaviorSubject.create<WifiP2pInfo>()
    private val deviceListSubject = BehaviorSubject.create<WifiP2pDeviceList>()
    private val p2pStateSubject = BehaviorSubject.create<P2pState>()
    private val onDisconnects = ConcurrentHashMap<WifiP2pDevice, ()-> Unit>()
    private val mListener = PeerListListener { value ->
        peerList.set(value.deviceList)
        LOG.e("peersListener fired: ${peerList.get().size}")
        deviceListSubject.onNext(value)
        value.deviceList.forEach { dev ->
           val onDisconnect = onDisconnects.remove(dev)
            if (onDisconnect != null) {
                onDisconnect()
            }
        }
    }
    private val peerList = AtomicReference<Collection<WifiP2pDevice>>(setOf())


    override fun setOnDisconnect(device: WifiP2pDevice, onDisconnect: () -> Unit) {
        onDisconnects[device] = onDisconnect
    }

    override fun connectedDevices(): Collection<WifiP2pDevice> {
        return peerList.get()
    }
    private fun p2pStateChangedAction(intent: Intent) {
        LOG.v("WIFI_P2P_STATE_CHANGED_ACTION")
        // Determine if Wifi P2P mode is enabledL
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
        try {
            manager.requestPeers(channel, mListener)
        } catch (exc: SecurityException) {
            LOG.e("securityException $exc")
        }
    }

    private fun connectionChangedAction(intent: Intent) {
        // Connection state changed!
        LOG.v("WIFI_P2P_CONNECTION_CHANGED_ACTION")
        val info = intent.getParcelableExtra<WifiP2pInfo>(EXTRA_WIFI_P2P_INFO)
        val network = intent.getParcelableExtra<NetworkInfo>(EXTRA_NETWORK_INFO)
        LOG.v( "wifi connected? ${network?.isConnected}")
        if (info != null) {
            connectionSubject.onNext(info)
        }
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
            WIFI_P2P_CONNECTION_CHANGED_ACTION -> connectionChangedAction(intent)
            WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> thisDeviceChangedAction(intent)
            else -> LOG.v("unhandled wifi p2p action $action")
        }
    }

    override fun observeP2pState(): Observable<P2pState> {
        return p2pStateSubject
    }

    override fun observeThisDevice(): Observable<WifiP2pDevice> {
        return thisDeviceChangedSubject
    }

    override fun observeConnectionInfo(): Observable<WifiDirectInfo> {
        return connectionSubject.
                map { i -> wifiDirectInfo(i) }

    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return deviceListSubject
    }

    override fun asReceiver(): BroadcastReceiver {
        return this
    }
}