package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.component
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanBroadcastReceiverImpl @Inject constructor() : ScanBroadcastReceiver, BroadcastReceiver() {

    @Inject
    lateinit var client: RxBleClient

    @Inject
    lateinit var state: BroadcastReceiverState

    @Inject
    lateinit var factory: ScatterbrainTransactionFactory

    @Inject
    lateinit var advertiser: Advertiser

    private val LOG by scatterLog()

    override fun onReceive(context: Context, intent: Intent) {
        if(context.checkCallingOrSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val component = context.getComponent()
        if (component != null) {
            component.inject(this)
            val result = client.backgroundScanner.onScanResultReceived(intent)
            try {
                LOG.v("broadcast ${result.size}")
                val lock = state.transactionLock.getAndSet(true)
                if (!lock) {
                    if (result.isNotEmpty()) {
                        val radioModule = factory.transaction().bluetoothLeRadioModule()
                        val disp = radioModule.startServer()
                            .doOnError { e -> e.printStackTrace() }
                            .mergeWith(
                                Observable.fromIterable(result)
                                    .concatMapSingle { res ->
                                        LOG.v("setting luid in response to scan")
                                        advertiser.setAdvertisingLuid()
                                            .andThen(radioModule.removeWifiDirectGroup(advertiser.randomizeLuidIfOld()))
                                            .toSingleDefault(res)
                                    }
                                    .concatMapMaybe { r -> radioModule.processScanResult(r) }
                                    .ignoreElements()
                                    .doOnDispose { state.transactionLock.set(false) }
                                    .doFinally {
                                        state.disposable.set(null)
                                        state.transactionLock.set(false)
                                    }

                            )

                            .subscribe(
                                { LOG.v("client transaction complete") },
                                { err -> LOG.e("client transaction error $err") }
                            )
                        state.disposable.getAndSet(disp)?.dispose()
                    }
                }
            } catch (exc: Exception) {
                state.transactionLock.set(false)
                LOG.e("exception in scan broadcastreceiver $exc")
            }
        }

    }
}