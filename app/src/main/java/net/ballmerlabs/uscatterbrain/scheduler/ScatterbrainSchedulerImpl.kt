package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.os.Parcelable
import android.os.PowerManager
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import com.polidea.rxandroidble2.scan.ScanSettings.SCAN_MODE_LOW_POWER
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.RouterState
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.*
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.loggerScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
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
    private val server: ManagedGattServer,
    private val leState: LeState,
    powerManager: PowerManager
) : ScatterbrainScheduler {
    private val LOG by scatterLog()
    private val pendingIntent = ScanBroadcastReceiver.newPendingIntent(context)
    private val discoveryLock = AtomicReference(false)
    override val isDiscovering: Boolean
        get() = discoveryLock.get()
    private var isAdvertising = false
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        context.getString(
            R.string.wakelock_tag
        )
    )
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

    /*
    * we should always hold a wakelock directly after the adapter wakes up the device
    * when a scatterbrain uuid is detected. The adapter is responsible for waking up the device
    * via offloaded scanning, but NOT for keeping it awake.
    */
    private fun acquireWakelock() {
        if (!wakeLock.isHeld)
            wakeLock.acquire((30 * 1000).toLong())
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld)
            wakeLock.release()
    }

    @Synchronized
    override fun start() {
        val discovering = discoveryLock.getAndSet(true)
        if (discovering) {
            broadcastRouterState(RouterState.DISCOVERING)
            return
        }
        state.shouldScan = true
        val disp = advertiser.startAdvertise()
            .andThen(server.startServer())
            .timeout(10, TimeUnit.SECONDS)
            .subscribe(
            {
             LOG.v("started advertise")
                client.backgroundScanner.scanBleDeviceInBackground(
                    pendingIntent,
                    ScanSettings.Builder()
                        .setScanMode(SCAN_MODE_LOW_POWER)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setShouldCheckLocationServicesState(false)
                        .setLegacy(true)
                        .build(),
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                        .build()
                )
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

    @Synchronized
    override fun stop(): Boolean {
        LOG.e("stop")
        val lock = discoveryLock.getAndSet(false)
        if (lock) {
            client.backgroundScanner.stopBackgroundBleScan(pendingIntent)
            val disp = advertiser.stopAdvertise().subscribe(
                {
                    state.shouldScan = false
                    leState.activeLuids.forEach { c ->
                        leState.updateDisconnected(c.key)
                    }
                    leState.activeLuids.clear()
                    server.stopServer()
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