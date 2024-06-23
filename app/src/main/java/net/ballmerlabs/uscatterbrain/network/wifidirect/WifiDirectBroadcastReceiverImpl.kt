package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.*
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.WifiDirectProvider
import net.ballmerlabs.uscatterbrain.WifiGroupSubcomponent
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver.P2pState
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * BroadcastReceiver for wifi direct related broadcasts.
 *
 * this class converts broadcasts into observables
 */
@Singleton
class WifiDirectBroadcastReceiverImpl @Inject constructor() : BroadcastReceiver(), WifiDirectBroadcastReceiver {

    @Inject
    lateinit var manager: WifiDirectProvider

    @Inject
    lateinit var bootstrapRequestProvider: BootstrapRequestSubcomponent.Builder

    @Inject
    lateinit var serverSocketManager: ServerSocketManager

    @Inject
    lateinit var wifiGroupSubcomponent: WifiGroupSubcomponent.Builder

    @Inject
    lateinit var leState: Provider<LeState>

    @Inject
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT)
    lateinit var timeoutScheduler: Scheduler

    @Inject
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
    lateinit var computationScheduler: Scheduler

    @Inject
    lateinit var advertiser: Provider<Advertiser>

    @Inject
    lateinit var radioModule: Provider<WifiDirectRadioModule>

    private val LOG by scatterLog()

    private val thisDeviceChangedSubject = BehaviorSubject.create<WifiP2pDevice>()
    private val connectionSubject = BehaviorSubject.create<WifiP2pInfo>()
    private val deviceListSubject = BehaviorSubject.create<WifiP2pDeviceList>()
    private val p2pStateSubject = BehaviorSubject.create<P2pState>()
    private val currentGroup = BehaviorSubject.create<Maybe<WifiGroupSubcomponent>>()
    private val ignoreShutdown = AtomicBoolean(false)

    private val mListener = PeerListListener { value ->
        peerList.set(value.deviceList)
        LOG.e("peersListener fired: ${peerList.get().size}")
        deviceListSubject.onNext(value)
    }
    private val peerList = AtomicReference<Collection<WifiP2pDevice>>(setOf())

    override fun <T> wrapConnection(connection: Maybe<T>): Maybe<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun <T> wrapConnection(connection: Observable<T>): Observable<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun <T> wrapConnection(connection: Single<T>): Single<T> {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun wrapConnection(connection: Completable): Completable {
        return connection
            .doOnSubscribe { ignoreShutdown.set(true) }
            .doFinally { ignoreShutdown.set(false) }
    }

    override fun removeCurrentGroup(): Completable {
        LOG.e("removeCurrentGroup called")

        return getCurrentGroup().flatMapCompletable { v ->
            v.groupHandle().shutdownUke()

            currentGroup.onNext(Maybe.empty())
            radioModule.get().removeGroup().onErrorComplete()
                .andThen(Completable.defer {
                    val entries = leState.get().connection()
                    Observable.fromIterable(entries).map { c ->
                        c.bluetoothLeRadioModule().cancelTransaction()
                    }.ignoreElements()
                }).concatWith( leState.get().dumpPeers(true) )


        }

    }

    override fun getCurrentGroup(timeout: Long): Maybe<WifiGroupSubcomponent> {
        return currentGroup.flatMapMaybe { v -> v }
            .doOnSubscribe { LOG.w("getCurrentGroup with timeout $timeout") }
            .timeout(timeout, TimeUnit.SECONDS, timeoutScheduler)
            .firstOrError()
            .toMaybe()
            .onErrorResumeNext(Maybe.empty())
            .doOnSuccess { v -> LOG.w("getCurrentGroup ${v.request().name}") }
            .doOnComplete { LOG.w("getCurrentGroup empty") }
    }

    override fun getCurrentGroup(): Maybe<WifiGroupSubcomponent> {
        return currentGroup.firstOrError().flatMapMaybe { v -> v }
            .doOnSuccess { v -> LOG.w("getCurrentGroup ${v.request().name}") }
            .doOnComplete { LOG.w("getCurrentGroup empty") }
    }

    override fun awaitCurrentGroup(): Single<WifiGroupSubcomponent> {
        return currentGroup
            .flatMapMaybe { v -> v }
            .firstOrError()
    }

    override fun createCurrentGroup(
        band: Int, groupInfo: WifiP2pGroup, connectionInfo: WifiDirectInfo, selfLuid: UUID
    ): Single<WifiGroupSubcomponent> {
        return serverSocketManager.getServerSocket().map { socket ->
            LOG.v("createCurrentGroup got serverSocket")
            val packet = bootstrapRequestProvider
                .wifiDirectArgs(
                    BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                        passphrase = groupInfo.passphrase,
                        name = groupInfo.networkName,
                        role = BluetoothLEModule.Role.ROLE_UKE,
                        band = band,
                        port = socket.socket.localPort,
                        ownerAddress = connectionInfo.groupOwnerAddress!!,
                        from = selfLuid
                    )
                ).build()!!.wifiBootstrapRequest()
            LOG.w("seme got serverSocket")
            val handle = wifiGroupSubcomponent
                .bootstrapRequest(packet)
                .serverSocket(socket)
                .info(
                    WifiSessionConfig(
                        wifiGroupInfo = WifiGroupInfo(
                            networkName = packet.name,
                            passphrase = packet.passphrase,
                            band = packet.band
                        ),
                        wifiDirectInfo = WifiDirectInfo(
                            groupFormed = true,
                            isGroupOwner = false,
                            groupOwnerAddress = packet.groupOwnerAdress,
                        )
                    )
                ).build()

            currentGroup.onNext(
                Maybe.just(
                    handle
                )
            )
            handle
        }


    }

    override fun createCurrentGroup(packet: WifiDirectBootstrapRequest): Single<WifiGroupSubcomponent> {
        return serverSocketManager.getServerSocket().map { socket ->
            LOG.w("seme got serverSocket")
            val handle = wifiGroupSubcomponent
                .bootstrapRequest(packet)
                .serverSocket(socket)
                .info(
                    WifiSessionConfig(
                        wifiGroupInfo = WifiGroupInfo(
                            networkName = packet.name,
                            passphrase = packet.passphrase,
                            band = packet.band
                        ),
                        wifiDirectInfo = WifiDirectInfo(
                            groupFormed = true,
                            isGroupOwner = false,
                            groupOwnerAddress = packet.groupOwnerAdress,
                        )
                    )
                ).build()

            currentGroup.onNext(
                Maybe.just(
                    handle
                )
            )
            handle
        }
    }

    override fun createCurrentGroup(packet: UpgradePacket): Single<WifiGroupSubcomponent> {
        return Single.defer {
            val req = WifiDirectBootstrapRequest.create(
                packet,
                bootstrapRequestProvider
            )
            createCurrentGroup(req)
        }
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
            val channel = manager.getChannel()
            if (channel != null) {
                manager.getManager()?.requestPeers(channel, mListener)
            }
        } catch (exc: SecurityException) {
            LOG.e("securityException $exc")
        }
    }

    private fun connectionChangedAction(intent: Intent) {
        // Connection state changed!
        val info = intent.getParcelableExtra<WifiP2pInfo>(EXTRA_WIFI_P2P_INFO)
        val network = intent.getParcelableExtra<NetworkInfo>(EXTRA_NETWORK_INFO)
        LOG.v( "wifi connected? ${network?.isConnected}")
        if (info != null) {
            LOG.v("WIFI_P2P_CONNECTION_CHANGED_ACTION ${info.groupFormed} ${info.isGroupOwner} ${info.groupOwnerAddress}")
            connectionSubject.onNext(info)
            /*
            if ((!info.groupFormed || info.groupOwnerAddress == null) && ! ignoreShutdown.get()) {
                val disp = removeCurrentGroup()
                    .subscribeOn(computationScheduler)
                    .observeOn(computationScheduler)
                    .subscribe()

                actionDisposable.getAndSet(disp)?.dispose()
            }

             */
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
                map { i -> WifiDirectInfo(
                    groupFormed = i.groupFormed,
                    isGroupOwner = i.isGroupOwner,
                    groupOwnerAddress = i.groupOwnerAddress
                ) }

    }

    override fun observePeers(): Observable<WifiP2pDeviceList> {
        return deviceListSubject
    }

    override fun asReceiver(): BroadcastReceiver {
        return this
    }
    init {
        currentGroup.onNext(Maybe.empty())
    }
}