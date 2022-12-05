package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.getComponent
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
            val lock = state.scanLock.getAndSet(true)
            if (!lock) {
                val disp = if (result.all { r -> leState.shouldConnect(r) }) {
                    LOG.e("LOCKED!")
                    val radioModule = factory.transaction().bluetoothLeRadioModule()
                    Observable.fromIterable(result)
                        .concatMapSingle { res ->
                            advertiser.setAdvertisingLuid()
                                .andThen(
                                    radioModule.removeWifiDirectGroup(advertiser.randomizeLuidIfOld())
                                        .onErrorComplete()
                                )
                                .toSingleDefault(res)
                        }
                        .filter { res -> leState.shouldConnect(res) && leState.updateConnected(leState.getAdvertisedLuid(res)!!)}
                        .concatMapMaybe { r ->
                            radioModule.removeWifiDirectGroup(advertiser.randomizeLuidIfOld())
                                .andThen(
                                    radioModule.processScanResult(r)
                                        .doOnSubscribe { LOG.v("subscribed processScanResult scanner") }
                                        .doOnError { err -> LOG.e("process scan result error $err") })
                        }
                        .ignoreElements()
                        .doOnError { err -> LOG.e("process scan result error $err") }
                        .onErrorComplete()
                        .doOnError { err -> LOG.e("scan error $err") }
                        .doFinally {
                            state.disposable.getAndSet(null)?.dispose()
                            state.scanLock.set(false)
                        }
                        .subscribe(
                            { LOG.v("client transaction complete") },
                            { err -> LOG.e("client transaction error $err") }
                        )
                } else {
                    advertiser.setAdvertisingLuid()
                        .doFinally {
                            state.disposable.getAndSet(null)?.dispose()
                            state.scanLock.set(false)
                        }
                        .subscribe(
                            { LOG.v("update advertising luid") },
                            { err -> LOG.e("client transaction error $err") }
                        )

                }
                state.disposable.getAndSet(disp)?.dispose()
            } else {
                LOG.v("skipping scan result due to lock")
            }
        } catch (exc: Exception) {
            state.scanLock.set(false)
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