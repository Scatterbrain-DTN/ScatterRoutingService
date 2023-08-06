package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import com.polidea.rxandroidble2.RxBleClient
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterProto
import net.ballmerlabs.uscatterbrain.ScatterProto.JustUkes
import net.ballmerlabs.uscatterbrain.ScatterProto.UUID
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.UkeAnnouncePacket
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.CLEAR_DATA
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

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
    lateinit var wifiDirectRadioModule: WifiDirectRadioModule

    @Inject
    lateinit var bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>

    @Inject
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
    lateinit var operationsScheduler: Scheduler

    private val LOG by scatterLog()

    private fun handle(context: Context, intent: Intent) {
        val r = client.backgroundScanner.onScanResultReceived(intent)
        val ukesSet = mutableListOf<UkeAnnouncePacket>()
        var clear = false
        for (result in r) {
            val uuid = leState.getAdvertisedLuid(result)
            if (uuid != null) {
                if (result.scanRecord.serviceData[ParcelUuid(CLEAR_DATA)] != null)
                    clear = true
                val ukes = result.scanRecord.serviceData[ParcelUuid(uuid)]
                if (ukes != null && ukes.isNotEmpty()) {
                    val packet = UkeAnnouncePacket(JustUkes.parseFrom(ukes))
                    ukesSet.add(packet)
                   // LOG.e("found ukes via scan ${packet.force.size}")
                }
            }

        }
        advertiser.ukes.putAll(ukesSet.flatMap { v -> v.force.entries }
            .associate { (k, v) -> Pair(k, v) })

        state.addTask {
            Completable.defer {
                if (r.isNotEmpty() && r.any { v -> !leState.shouldConnect(v) }) {
                    advertiser.setAdvertisingLuid()
                } else {
                    Completable.complete()
                }
            }.andThen(state.batch(r))
                .flatMapCompletable { result ->
                    scatterbrainScheduler.acquireWakelock()
                  //  scatterbrainScheduler.pauseScan()
                    // LOG.w("bare wifidirect size ${ukesSet.size} ${ukesSet.filter { p -> p.tooSmall }.size}")
                    /*
                     Flowable.defer { if (ukesSet.size > 1) Flowable.fromIterable(ukesSet) else Flowable.empty() }
                        .filter { !wifiDirectRadioModule.getForceUke() }
                        .filter { p -> !p.tooSmall }
                        .flatMap { p -> Flowable.fromIterable(p.force.entries) }
                        .filter { p -> leState.updateActive(p.key) }
                        .map { (luid, p) ->
                            LOG.w("bare wifi with force $luid")
                            val upgrade = UpgradePacket(p.packet)
                            val request = WifiDirectBootstrapRequest.create(
                                upgrade,
                                BluetoothLEModule.Role.ROLE_SUPERSEME,
                                bootstrapRequestProvider.get(),
                                FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
                            )
                            LOG.w("skipping ble, wifi direct to ${request.name} ${request.passphrase}")
                            wifiDirectRadioModule.bootstrapSeme(
                                request.name,
                                request.passphrase,
                                FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO,
                                request.port,
                                advertiser.getHashLuid()
                            )
                        }
                        .ignoreElements()
                        .doOnError { err ->
                            LOG.e("process scan result error $err")
                        }.andThen(
                     */

                   Completable.defer {
                            val luid = leState.getAdvertisedLuid(result)
                            if (ukesSet.flatMap { p -> p.force.keys }
                                    .find { p -> p == luid } == null) {
                                if (luid != null && leState.updateActive(luid)) {
                                    leState.processScanResult(luid, result.bleDevice)
                                        .doOnSubscribe { LOG.v("subscribed processScanResult scanner") }
                                        .doOnError { err ->
                                            LOG.e("process scan result error $err")
                                        }
                                } else {
                                    Maybe.empty()
                                }
                                    .ignoreElement()
                                    .doOnError { err ->
                                        LOG.e("process scan result error $err")
                                    }
                                    .onErrorComplete()
                            } else {
                                Completable.complete()
                            }
                        }
                      // .doOnDispose { scatterbrainScheduler.unpauseScan() }
                       .doFinally {
                          //  scatterbrainScheduler.unpauseScan()
                            if (state.clear(clear)) {
                                LOG.w("clear set, dumping ukes")
                                advertiser.ukes.clear()
                            }
                            state.tlock.set(false)
                        }

                }
                .doFinally {
                    //   scatterbrainScheduler.unpauseScan()
                    state.connectLock.set(false)
                }
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