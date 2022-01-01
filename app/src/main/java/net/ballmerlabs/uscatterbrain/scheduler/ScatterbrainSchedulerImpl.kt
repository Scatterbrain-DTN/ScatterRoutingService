package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
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
        private val bluetoothLEModule: BluetoothLEModule,
        private val context: Context,
        powerManager: PowerManager
        ) : ScatterbrainScheduler {
    private val discoveryLock = AtomicReference(false)
    override val isDiscovering
        get() = discoveryLock.get()
    private var isAdvertising = false
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        context.getString(
            R.string.wakelock_tag
        ))
    private val globalDisposable = AtomicReference<Disposable?>()
    private fun broadcastTransactionResult(transactionStats: HandshakeResult) {
        val intent = Intent(ScatterbrainApi.BROADCAST_EVENT)
        
        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, transactionStats)
        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
    }

    /*
    * we should always hold a wakelock directly after the adapter wakes up the device
    * when a scatterbrain uuid is detected. The adapter is responsible for waking up the device
    * via offloaded scanning, but NOT for keeping it awake.
    */
    private fun acquireWakelock() {
        if (!wakeLock.isHeld)
            wakeLock.acquire((10 * 60 * 1000).toLong())
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld)
            wakeLock.release()
    }

    @Synchronized
    override fun start() {
        val discovering = discoveryLock.getAndSet(true)
        if (discovering) {
            return
        }
        isAdvertising = true
        bluetoothLEModule.startServer()
        val compositeDisposable = CompositeDisposable()
        val d2 = bluetoothLEModule.observeTransactionStatus()
            .subscribe(
                { res ->
                    if(res) {
                        Log.v(TAG, "transaction started, acquiring wakelock")
                        acquireWakelock()
                    }
                    else {
                        Log.v(TAG, "transaction completed, releasing wakelock")
                        releaseWakeLock()
                    }
                },
                { err ->
                    Log.e(TAG, "error in observeTransactionStatus: wakelocks broked")
                    err.printStackTrace()
                }
            )
        val d = bluetoothLEModule.startAdvertise()
            .andThen(bluetoothLEModule.discoverForever()
                .doOnDispose { discoveryLock.set(false) })
                .subscribe(
                    { res ->
                        Log.v(TAG, "finished transaction: ${res.success}")
                    },
                    { err ->
                    Log.e(TAG, "error in transaction: $err")
                    err.printStackTrace()
                })
        compositeDisposable.add(d)
        compositeDisposable.add(d2)
        val disp = globalDisposable.getAndSet(compositeDisposable)
        disp?.dispose()
    }

    @Synchronized
    override fun stop(): Boolean {
        if (!isAdvertising) {
            return false
        }
        isAdvertising = false
        bluetoothLEModule.stopAdvertise()
            .subscribe()
        bluetoothLEModule.stopServer()
        val disp = globalDisposable.getAndSet(null)
        disp?.dispose()
        return true
    }

    override val isPassive: Boolean
        get() = isAdvertising && !isDiscovering

    companion object {
        const val TAG = "Scheduler"
    }

    init {
        val d = this.bluetoothLEModule.observeCompletedTransactions()
                .subscribe({ transactionStats -> broadcastTransactionResult(transactionStats) }
                ) { Log.e(TAG, "fatal error, transaction relay somehow called onError") }
    }
}