package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanBroadcastReceiverImpl @Inject constructor() : ScanBroadcastReceiver, BroadcastReceiver() {

    private val LOG by scatterLog()

    @Inject
    lateinit var radioModule: BluetoothLEModule

    @Inject
    lateinit var client: RxBleClient

    @Inject
    lateinit var state: BroadcastReceiverState

    override fun onReceive(context: Context, intent: Intent) {
       val component =  ScatterRoutingService.componentVal
        if (component != null) {
            component.inject(this)
            if(!this::state.isInitialized) {
                return
            }
            try {
                val lock = state.transactionLock.getAndSet(true)
                if (!lock) {
                    val result = client.backgroundScanner.onScanResultReceived(intent)
                    val disp = Observable.fromIterable(result)
                        .concatMapSingle { res ->
                            radioModule.setAdvertisingLuid()
                                .andThen(radioModule.removeWifiDirectGroup(radioModule.randomizeLuidIfOld()))
                                .toSingleDefault(res)
                        }
                        .concatMapMaybe { r -> radioModule.processScanResult(r) }
                        .doFinally {
                            state.disposable.set(null)
                            state.transactionLock.set(false)
                        }
                        .doOnDispose { state.transactionLock.set(false) }
                        .subscribe(
                            { next -> LOG.v("client transaction result ${next.success}") },
                            { err -> LOG.e("client transaction error $err") }
                        )
                    state.disposable.getAndSet(disp)?.dispose()

                }
            } catch (exc: Exception) {
                if (::state.isInitialized) {
                    state.transactionLock.set(false)
                }
                LOG.e("exception in scan broadcastreceiver $exc")
            }
        }
    }
}