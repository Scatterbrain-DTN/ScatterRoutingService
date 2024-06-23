package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.ParcelUuid
import android.os.Parcelable
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import com.polidea.rxandroidble2.scan.ScanSettings.SCAN_MODE_LOW_POWER
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import kotlinx.collections.immutable.immutableListOf
import kotlinx.collections.immutable.persistentListOf
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.RouterState
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceBackend
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.WakeLockProvider
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.ScanBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddr
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddrs
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiSessionState
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiSubcomponent
import net.ballmerlabs.uscatterbrain.network.proto.IdentityPacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The purpose of the scheduler is to manage the global state of the router
 * currently this means switching from active to passive discovery or disabling
 * the router
 *
 * This should get more complex in the future when transport modules are plugins
 */
@Singleton
class ScatterbrainSchedulerImpl @Inject constructor(
    private val context: Context,
    private val firebaseWrapper: FirebaseWrapper,
    private val advertiser: Advertiser,
    private val client: RxBleClient,
    private val state: BroadcastReceiverState,
    private val leState: LeState,
    private val datastore: ScatterbrainDatastore,
    private val wifiDirectBroadcastReceiver: WifiDirectBroadcastReceiver,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val operationsScheduler: Scheduler,
    val serverSocketManager: ServerSocketManager,
    val desktopBuilder: Provider<DesktopApiSubcomponent.Builder>,
    private val powerManager: WakeLockProvider,
    val broadcaster: Broadcaster,
    val wifiManager: WifiManager,
    val preferences: RouterPreferences
) : ScatterbrainScheduler {
    private val LOG by scatterLog()
    private var pendingIntent = ScanBroadcastReceiver.newPendingIntent(context)
   // private var pendingIntentLegacy = ScanBroadcastReceiver.newPendingIntentLegacy(context)
    private val discoveryLock = AtomicReference(false)
    override val isDiscovering: Boolean
        get() = discoveryLock.get()
    private var isAdvertising = false

    private val desktopApi = AtomicReference<DesktopApiSubcomponent>(null)

    private val globalDisposable = AtomicReference<Disposable?>()
    override fun broadcastTransactionResult(transactionStats: HandshakeResult): Completable {

        return datastore.getStats(transactionStats).flatMapCompletable { res ->
            LOG.v("send transaction result ${res.metrics.size}")
            Completable.defer {
                val intent = Intent(ScatterbrainApi.BROADCAST_EVENT)
                intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, res)
                context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)

                Completable.complete()
            }
        }
    }

    override fun broadcastIdentities(identities: List<IdentityPacket>): Completable {
        return desktopApi.get()?.desktopServer()?.broadcastIdentities(identities)
            ?:Completable.complete()
    }

    override fun broadcastMessages(messages: List<DbMessage>): Completable {
        return desktopApi.get()?.desktopServer()?.broadcastMessages(messages)
            ?:Completable.complete()
    }

    private fun broadcastRouterState(routerState: RouterState) {
        val intent = Intent(ScatterbrainApi.STATE_EVENT)
        intent.putExtra(ScatterbrainApi.EXTRA_ROUTER_STATE, routerState as Parcelable)
        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
    }

    override fun authorizeDesktop(fingerprint: ByteArray, authorize: Boolean): Completable {
        return Completable.defer {
            val dapi = desktopApi.get()
            dapi?.desktopServer()?.authorize(fingerprint, authorize)
                ?: Completable.error(IllegalStateException("desktop api not started"))
        }
    }


    override fun confirmIdentityImport(handle: UUID, identity: UUID): Completable {
        return Completable.defer {
            desktopApi.get()!!.desktopServer().confirm(handle, identity)
        }
    }

    override fun startDesktopServer(name: String): Completable {
        LOG.w("startDesktopServer called")
        return serverSocketManager.getServerSocket()
            .doOnSuccess { sock ->
                desktopApi.set(
                    desktopBuilder.get()
                        .serviceConfig(DesktopApiSubcomponent.ServiceConfig(
                            name = name
                        ))
                        .portSocket(sock)
                        .build().apply {
                            desktopServer().serve()
                        }
                )
            }.ignoreElement()
            .doFinally { LOG.w("startDesktopServer completed") }
    }

    override fun stopDesktopServer() {
        desktopApi.getAndSet(null)?.desktopServer()?.shutdown()
    }


    override fun pauseScan() {
        LOG.w("pauseScan")
        client.backgroundScanner.stopBackgroundBleScan(pendingIntent)
       // client.backgroundScanner.stopBackgroundBleScan(pendingIntentLegacy)
        /*
        PendingIntent.getBroadcast(
            context,
            SCAN_REQUEST_CODE,
            Intent(context, ScanBroadcastReceiverImpl::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        ).cancel()
         */
        //pendingIntent.cancel()
    }

    private fun registerReceiver(): Completable {
        return Completable.fromAction {
            LOG.v("registering broadcast receiver")
            val intentFilter = IntentFilter()
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)

            // Indicates a change in the list of available peers.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)

            // Indicates the state of Wi-Fi P2P connectivity has changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)

            // Indicates this device's details have changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            context.applicationContext.registerReceiver(wifiDirectBroadcastReceiver.asReceiver(), intentFilter)

        }.subscribeOn(operationsScheduler)
    }

    private fun unregisterReceiver(): Completable {
        return Completable.fromAction {
            context.applicationContext.unregisterReceiver(wifiDirectBroadcastReceiver.asReceiver())
        }.subscribeOn(operationsScheduler)
            .doOnError { err -> LOG.w("failed to unregister receiver $err") }
            .onErrorComplete()
    }

    override fun unpauseScan() {
        LOG.w("unpauseScan")
        /*
        client.backgroundScanner.scanBleDeviceInBackground(
            pendingIntentLegacy,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setShouldCheckLocationServicesState(true)
                .setLegacy(true)
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID_LEGACY))
                .build()
        )

         */


        client.backgroundScanner.scanBleDeviceInBackground(
            pendingIntent,
            ScanSettings.Builder()
                .setScanMode(SCAN_MODE_LOW_POWER)
                .setShouldCheckLocationServicesState(true)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(false)
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID_NEXT))
                .build()
        )
    }

    /*
    * we should always hold a wakelock directly after the adapter wakes up the device
    * when a scatterbrain uuid is detected. The adapter is responsible for waking up the device
    * via offloaded scanning, but NOT for keeping it awake.
    */
    override fun acquireWakelock() {
        powerManager.hold()
    }

    override fun releaseWakeLock() {
        powerManager.release()
    }

    override fun start() {
        val discovering = discoveryLock.getAndSet(true)
        if (discovering) {
            broadcastRouterState(RouterState.DISCOVERING)
            return
        }
        pauseScan()
        state.shouldScan = true
        val disp = broadcastTransactionResult(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
            .andThen(Observable.just(client.state))
            .concatWith(client.observeStateChanges())
            .switchMapCompletable { state ->
            LOG.w("RxAndroidBle state change $state")
            when(state) {
                RxBleClient.State.READY -> {
                    LOG.w("" +
                            "ble enabled, resuming")
                    unpauseScan()
                    broadcastRouterState(RouterState.DISCOVERING)
                    registerReceiver()
                        .andThen(advertiser.startAdvertise(advertiser.getHashLuid()))
                        .andThen(leState.startServer())
                        .andThen(preferences.getBoolean(context.getString(R.string.pref_desktop), false)
                            .toSingle(false)
                            .flatMapCompletable { v ->
                                preferences.getString(context.getString(R.string.pref_desktop_name), "")
                                    .flatMapCompletable { name ->
                                        LOG.v("autostart desktop $v name=$name")


                                        if (desktopApi.get() == null && v)
                                            startDesktopServer(name)
                                        else
                                            Completable.complete()
                                    }
                            })
                        .timeout(10, TimeUnit.SECONDS)
                }
                else -> {
                    LOG.w("ble disabled, pausing")
                    broadcastRouterState(RouterState.OFFLINE)
                    pauseScan()
                    leState.dumpPeers(true).andThen(unregisterReceiver())
                        .andThen(advertiser.stopAdvertise())
                        .andThen(leState.stopServer())
                }

            }
        }
            .subscribe(
                {
                    LOG.v("started advertise")
                    isAdvertising = true
                },
                { e ->
                    LOG.e("failed to start: $e")
                    e.printStackTrace()
                    firebaseWrapper.recordException(e)
                    broadcastRouterState(RouterState.ERROR)
                }
            )

        globalDisposable.getAndSet(disp)?.dispose()

    }

    override fun stop(): Boolean {
        LOG.e("stop")
        val lock = discoveryLock.getAndSet(false)
        if (lock) {
            pauseScan()
            val disp = unregisterReceiver().andThen(advertiser.stopAdvertise()).subscribe(
                {
                    //broadcastReceiverState.dispose()
                    state.shouldScan = false
                    leState.stopServer().blockingAwait()
                    leState.dumpPeers(true).blockingAwait()
                    globalDisposable.getAndSet(null)?.dispose()
                    broadcastRouterState(RouterState.OFFLINE)
                },
                { err -> LOG.e("failed to stop advertise ") }
            )
        }
        return lock
    }

    override val isPassive: Boolean
        get() = isAdvertising && !isDiscovering

    init {

    }
}