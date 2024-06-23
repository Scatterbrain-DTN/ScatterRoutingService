package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.akaita.java.rxjava2debug.extensions.RxJavaAssemblyException
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.TransactionError
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.Logger
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Since BroadcastReceivers are reinstantiated when operating, we need to store any persistent state
 * in a helper class
 */
@Singleton
class BroadcastReceiverState @Inject constructor(
    val advertiser: Advertiser,
    val leState: Provider<LeState>,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) val scheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_ADVERTISE) val batchScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler,
    val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    val scatterbrainScheduler: Provider<ScatterbrainScheduler>

) {
    private val LOG by scatterLog()
    private val disposable = AtomicReference<Disposable?>(null)
    private val batchDisposables = ConcurrentHashMap<UUID, Disposable>()
    private val batchCounter = AtomicInteger()
    private val batch = ConcurrentHashMap<ScanResult, Boolean>()
    val connectLock = AtomicBoolean()
    var shouldScan = false
    val tlock = AtomicBoolean(false)
    val reset = AtomicInteger(0)

    /**
     * Stores a number of ScanResults until count is reached, then interanlly process them via the
     * provided closure
     *
     * @param scanResult list of scan results from scanner
     * @param count number of results to batch
     * @param func closure returning completable called when enough results are batched
     */
    fun batch(scanResult: List<ScanResult>, count: Int = 1) {
        val disp = Completable.fromAction {
            batch.putAll(scanResult.distinctBy { v -> v.bleDevice.macAddress }
                .map { r -> Pair(r, true) })
            val c = batchCounter.accumulateAndGet(count) { v, acc ->
                val next = v + 1
                if (next > acc) {
                    0
                } else {
                    next
                }
            }
            if (c >= count && batch.isNotEmpty()) {
                val out = batch.keys().toList()
                batch.clear()
                if (!tlock.getAndSet(true)) {
                    for (result in out.distinctBy { v -> v.bleDevice.macAddress }) {
                        val luid = leState.get().getAdvertisedLuid(result)
                        if (luid != null) {
                            val d = batchDisposables.computeIfAbsent(luid) { d ->
                                Completable.defer {
                                    val luid = leState.get().getAdvertisedLuid(result)
                                    if (luid != null && leState.get().updateActive(luid)) {
                                        scatterbrainScheduler.get().acquireWakelock()
                                        leState.get().processScanResult(luid, result.bleDevice)
                                            .doOnComplete { LOG.w("processScanResult from scanner completed") }
                                            .doOnSubscribe { LOG.v("subscribed processScanResult scanner") }
                                    } else {
                                        //    LOG.v("skipping scan result ${result.bleDevice.macAddress} updateActive fail")
                                        Completable.complete()
                                    }
                                        .doOnError { e ->
                                            LOG.e("process scan result error $e $luid")
                                            if (luid != null) {
                                                leState.get().updateGone(luid, e)
                                            } else if (e is TransactionError) {
                                                leState.get().updateGone(e.luid, e)
                                            }
                                            if (e is RxJavaAssemblyException) {
                                                LOG.e(e.stacktrace())
                                            }
                                            e.printStackTrace()
                                        }
                                        .onErrorComplete()
                                }
                                    // .doOnDispose { scatterbrainScheduler.unpauseScan() }
                                    .doFinally {
                                        tlock.set(false)
                                    }
                                    .doFinally {
                                        //   scatterbrainScheduler.unpauseScan()
                                        connectLock.set(false)
                                    }
                                    .subscribeOn(batchScheduler)
                                    .observeOn(scheduler)
                                    .doFinally { batchDisposables.remove(luid) }
                                    .subscribe()
                            }
                            if (d.isDisposed) {
                                batchDisposables.remove(luid)
                            }
                        } else {
                            LOG.w("tried to batch with null luid")
                        }
                    }
                }
            }
        }.subscribeOn(batchScheduler)
            .observeOn(scheduler)
            .subscribe(
            {},
            {err -> LOG.w("scan result err") }
        )
    }


    /**
     * Terminates the batch handler for a given device
     * @param luid luid of the remote peer
     */
    fun killBatch(luid: UUID) {
        batchDisposables.remove(luid)?.dispose()
        dispose()
    }

    /**
     * Terminates all running tasks
     */
    fun killall() {
        for((_, disp) in batchDisposables.entries) {
            disp.dispose()
        }
        batchDisposables.clear()
        dispose()
    }

    fun dispose() {
        disposable.getAndSet(null)?.dispose()
    }
}