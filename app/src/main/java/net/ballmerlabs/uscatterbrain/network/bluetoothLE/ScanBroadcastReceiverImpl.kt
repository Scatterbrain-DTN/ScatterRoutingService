package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.component
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

class ScanBroadcastReceiverImpl : ScanBroadcastReceiver, BroadcastReceiver() {

    @Inject
    lateinit var client: RxBleClient

    @Inject
    lateinit var state: BroadcastReceiverState

    @Inject
    lateinit var factory: ScatterbrainTransactionFactory

    @Inject
    lateinit var advertiser: Advertiser

    @Inject
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
    lateinit var computeScheduler: Scheduler

    private val LOG by scatterLog()

    private fun handle(context: Context, intent: Intent) {
        val result = client.backgroundScanner.onScanResultReceived(intent)
        try {
            val lock = state.transactionLock.getAndSet(true)
            if (!lock) {
                if (result.isNotEmpty()) {
                    val radioModule = factory.transaction().bluetoothLeRadioModule()
                    val disp = radioModule.startServer()
                        .subscribeOn(computeScheduler)
                        .observeOn(computeScheduler)
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
                                .doFinally {
                                    state.disposable.set(null)
                                    state.transactionLock.set(false)
                                }

                        )

                        .subscribe(
                            { LOG.v("client transaction complete") },
                            { err -> LOG.e("client transaction error $err") }
                        )
                    state.disposable.getAndSet(disp)
                }
            }
        } catch (exc: Exception) {
            state.transactionLock.set(false)
            LOG.e("exception in scan broadcastreceiver $exc")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val component = context.getComponent()
        if (component != null) {
            component.inject(this)
            handle(context, intent)
        }

    }
}