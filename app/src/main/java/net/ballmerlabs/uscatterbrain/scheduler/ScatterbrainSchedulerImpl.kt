package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.util.Log
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler.RoutingServiceState
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
        private val context: Context
) : ScatterbrainScheduler {
    private val mState: AtomicReference<RoutingServiceState> = AtomicReference(RoutingServiceState.STATE_SUSPEND)
    override var isDiscovering = false
        private set
    private var isAdvertising = false
    private val globalDisposable = AtomicReference<Disposable?>()
    private fun broadcastTransactionResult(transactionStats: HandshakeResult?) {
        val intent = Intent(ScatterbrainApi.BROADCAST_EVENT)
        
        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, transactionStats)
        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
    }

    override val routingServiceState: RoutingServiceState
        get() = mState.get()

    @Synchronized
    override fun start() {
        if (isAdvertising) {
            return
        }
        isAdvertising = true
        bluetoothLEModule.startAdvertise()
        bluetoothLEModule.startServer()
        val d = bluetoothLEModule.discoverForever()
                .doOnSubscribe { isDiscovering = true }
                .doOnDispose { isDiscovering = false }
                .subscribe(
                        { res -> Log.v(TAG, "finished transaction: ${res.success}") }
                ) { err -> Log.e(TAG, "error in transaction: $err") }
        val disp = globalDisposable.getAndSet(null)
        disp?.dispose()
    }

    @Synchronized
    override fun stop(): Boolean {
        if (!isAdvertising) {
            return false
        }
        isAdvertising = false
        bluetoothLEModule.stopAdvertise()
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
        val d = this.bluetoothLEModule.observeTransactions()
                .subscribe({ transactionStats: HandshakeResult? -> broadcastTransactionResult(transactionStats) }
                ) { Log.e(TAG, "fatal error, transaction relay somehow called onError") }
    }
}