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
    lateinit var gattServer: ManagedGattServer

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
                LOG.e("LOCKED!")
                val radioModule = factory.transaction().bluetoothLeRadioModule()
                val disp = gattServer.startServer().flatMapCompletable { server ->
                    Observable.fromIterable(result)
                        .concatMapSingle { res ->
                            advertiser.setAdvertisingLuid()
                                .andThen(
                                    radioModule.removeWifiDirectGroup(advertiser.randomizeLuidIfOld())
                                        .onErrorComplete()
                                )
                                .toSingleDefault(res)
                        }
                        .filter { res ->
                            leState.shouldConnect(res) && leState.updateConnected(
                                leState.getAdvertisedLuid(
                                    res
                                )!!
                            )
                        }
                        .concatMapMaybe { r ->
                            radioModule.removeWifiDirectGroup(advertiser.randomizeLuidIfOld())
                                .andThen(
                                    radioModule.processScanResult(r, server)
                                        .doOnSubscribe { LOG.v("subscribed processScanResult scanner") }
                                        .doOnError { err -> LOG.e("process scan result error $err") })
                        }
                        .ignoreElements()
                        .doOnError { err -> LOG.e("process scan result error $err") }
                        .onErrorComplete()

                }
                    .doOnDispose { LOG.e("scan disposed") }
                    .subscribe(
                        { LOG.v("client transaction complete") },
                        { err -> LOG.e("client transaction error $err") }
                    )
                state.addDisposable(disp)

            } else {
                val disp = advertiser.setAdvertisingLuid()
                    .subscribe(
                        {  },
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