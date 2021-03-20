package net.ballmerlabs.uscatterbrain.scheduler

import android.content.Context
import android.content.Intent
import android.util.Log
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler.RoutingServiceState
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScatterbrainSchedulerImpl @Inject constructor(
        wifiDirectRadioModule: WifiDirectRadioModule,
        bluetoothLEModule: BluetoothLEModule,
        scatterbrainDatastore: ScatterbrainDatastore,
        private val context: Context
) : ScatterbrainScheduler {
    private val mState: AtomicReference<RoutingServiceState>
    private val bluetoothLEModule: BluetoothLEModule
    private val wifiDirectRadioModule: WifiDirectRadioModule
    private val datastore: ScatterbrainDatastore
    override var isDiscovering = false
        private set
    private var isAdvertising = false
    private val globalDisposable = AtomicReference<Disposable?>()
    private fun broadcastTransactionResult(transactionStats: HandshakeResult?) {
        val intent = Intent(context.getString(R.string.broadcast_message))
        
        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, transactionStats)
        context.sendBroadcast(intent, context.getString(R.string.permission_access))
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
                .doOnSubscribe { disp: Disposable? -> isDiscovering = true }
                .doOnDispose { isDiscovering = false }
                .subscribe(
                        { res: HandshakeResult? -> Log.v(TAG, "finished transaction: $res") }
                ) { err: Throwable -> Log.e(TAG, "error in transaction: $err") }
        globalDisposable.getAndUpdate { disp: Disposable? ->
            disp?.dispose()
            d
        }
    }

    @Synchronized
    override fun stop(): Boolean {
        if (!isAdvertising) {
            return false
        }
        isAdvertising = false
        bluetoothLEModule.stopAdvertise()
        bluetoothLEModule.stopServer()
        globalDisposable.getAndUpdate { disp: Disposable? ->
            disp?.dispose()
            null
        }
        return true
    }

    override val isPassive: Boolean
        get() = isAdvertising && !isDiscovering

    companion object {
        const val TAG = "Scheduler"
    }

    init {
        mState = AtomicReference(RoutingServiceState.STATE_SUSPEND)
        this.wifiDirectRadioModule = wifiDirectRadioModule
        this.bluetoothLEModule = bluetoothLEModule
        datastore = scatterbrainDatastore
        val d = this.bluetoothLEModule.observeTransactions()
                .subscribe({ transactionStats: HandshakeResult? -> broadcastTransactionResult(transactionStats) }
                ) { err: Throwable? -> Log.e(TAG, "fatal error, transaction relay somehow called onError") }
    }
}