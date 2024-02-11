package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.ParcelUuid
import android.os.Parcelable
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import com.polidea.rxandroidble2.scan.ScanSettings.SCAN_MODE_LOW_POWER
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.RouterState
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.WakeLockProvider
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.ScanBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
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
    private val wifiDirectBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val wifiDirectRadioModule: WifiDirectRadioModule,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val operationsScheduler: Scheduler,
    private val powerManager: WakeLockProvider
) : ScatterbrainScheduler {
    private val LOG by scatterLog()
    private var pendingIntent = ScanBroadcastReceiver.newPendingIntent(context)
    private val discoveryLock = AtomicReference(false)
    override val isDiscovering: Boolean
        get() = discoveryLock.get()
    private var isAdvertising = false

    private val globalDisposable = AtomicReference<Disposable?>()
    private fun broadcastTransactionResult(transactionStats: HandshakeResult) {
        val intent = Intent(ScatterbrainApi.BROADCAST_EVENT)

        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, transactionStats)
        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
    }

    private fun broadcastRouterState(routerState: RouterState) {
        val intent = Intent(ScatterbrainApi.STATE_EVENT)
        intent.putExtra(ScatterbrainApi.EXTRA_ROUTER_STATE, routerState as Parcelable)
        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
    }

    override fun pauseScan() {
        client.backgroundScanner.stopBackgroundBleScan(pendingIntent)
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
        client.backgroundScanner.scanBleDeviceInBackground(
            pendingIntent,
            ScanSettings.Builder()
                .setScanMode(SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setShouldCheckLocationServicesState(true)
                .setLegacy(false)
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
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
        powerManager.releaseAll()
    }

    override fun start() {
        val discovering = discoveryLock.getAndSet(true)
        if (discovering) {
            broadcastRouterState(RouterState.DISCOVERING)
            return
        }
        pauseScan()
        state.shouldScan = true
        val disp = registerReceiver()
            .andThen(wifiDirectRadioModule.removeGroup().onErrorComplete())
            .andThen(advertiser.startAdvertise())
            .andThen(leState.startServer())
            .timeout(10, TimeUnit.SECONDS)
            .doOnComplete { unpauseScan() }
            .subscribe(
                {
                    LOG.v("started advertise")
                    broadcastRouterState(RouterState.DISCOVERING)
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
            client.backgroundScanner.stopBackgroundBleScan(pendingIntent)
            val disp = unregisterReceiver().andThen(advertiser.stopAdvertise()).subscribe(
                {
                    //broadcastReceiverState.dispose()
                    state.shouldScan = false
                    leState.connectionCache.forEach { c ->
                        leState.updateDisconnected(c.key)
                    }
                    leState.connectionCache.clear()
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