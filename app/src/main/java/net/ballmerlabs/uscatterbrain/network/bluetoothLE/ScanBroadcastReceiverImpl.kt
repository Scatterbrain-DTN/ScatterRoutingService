package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Named

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
    lateinit var leState: LeState

    @Inject
    lateinit var scatterbrainScheduler: ScatterbrainScheduler

    @Inject
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
    lateinit var computeScheduler: Scheduler

    private val LOG by scatterLog()

    private fun handle(context: Context, intent: Intent) {
        if (!state.shouldScan) {
            client.backgroundScanner.stopBackgroundBleScan(
                ScanBroadcastReceiver.newPendingIntent(
                    context
                )
            )
            return
        }
        val result = client.backgroundScanner.onScanResultReceived(intent)
        try {
            if (result.all { r -> leState.shouldConnect(r) }) {
                if (!state.connectLock.getAndSet(true)) {
                    // scatterbrainScheduler.pauseScan()
                    val disp = Observable.fromIterable(result)
                        .distinct { v -> leState.getAdvertisedLuid(v) }
                        .concatMapMaybe { r ->
                            val luid = leState.getAdvertisedLuid(r)
                            if (luid != null && leState.shouldConnect(r)) {
                                leState.processScanResult(luid, r.bleDevice)
                                    .doOnSubscribe { LOG.v("subscribed processScanResult scanner") }
                                    .doOnError { err ->
                                        err.printStackTrace()
                                        LOG.e("process scan result error $err")
                                    }
                            } else {
                                Maybe.empty()
                            }
                        }
                        .ignoreElements()
                        .doOnError { err ->
                            err.printStackTrace()
                            LOG.e("process scan result error $err")
                        }
                        .onErrorComplete()
                        .doFinally {
                            //   scatterbrainScheduler.unpauseScan()
                            state.connectLock.set(false)
                        }
                        .subscribe(
                            { LOG.v("client transaction complete") },
                            { err -> LOG.e("client transaction error $err") }
                        )
                    state.addDisposable(disp)
                }


            } else {
                val disp = advertiser.setAdvertisingLuid()
                    .subscribe(
                        { },
                        { err -> LOG.e("failed to update advertise luid $err") }
                    )
                state.advertiseDisposable(disp)

            }
        } catch (exc: Exception) {
            state.dispose()
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