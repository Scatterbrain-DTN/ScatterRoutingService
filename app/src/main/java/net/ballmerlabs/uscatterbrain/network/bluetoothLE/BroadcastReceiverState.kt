package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.os.ParcelUuid
import com.akaita.java.rxjava2debug.extensions.RxJavaAssemblyException
import com.polidea.rxandroidble2.scan.ScanResult
import io.ktor.util.encodeBase64
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.network.TransactionError
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.MERKLE_DATA
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) val timeoutScheduler: Scheduler,
    val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    val scatterbrainScheduler: Provider<ScatterbrainScheduler>,
    val database: Datastore,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CALLBACKS) val sched: Scheduler,
) {
    private val LOG by scatterLog()
    private val disposable = AtomicReference<Disposable?>(null)
    private val batchDisposables = ConcurrentHashMap<UUID, Disposable>()
    private val batchCounter = AtomicInteger()
    private val batch = ConcurrentHashMap<ScanResult, Boolean>()
    val connectLock = AtomicBoolean()
    var shouldScan = false
    val reset = AtomicInteger(0)

    /**
     * Stores a number of ScanResults until count is reached, then interanlly process them via the
     * provided closure
     *
     * @param scanResult list of scan results from scanner
     * @param count number of results to batch
     * @param func closure returning completable called when enough results are batched
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun batch(scanResult: List<ScanResult>, count: Int = 1) {

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

                for (result in out.distinctBy { v -> v.bleDevice.macAddress }) {
                    val luid = leState.get().getAdvertisedLuid(result)
                    if (luid != null) {
                        if (batchDisposables.containsKey(luid))
                            LOG.v("lock held for $luid, waiting for scan")
                        val d = batchDisposables.computeIfAbsent(luid) { d ->
                            database.merkleDao().getDefaultRoot().flatMapCompletable { root ->
                                    val mk = ParcelUuid(MERKLE_DATA)
                                    val remoteroot = result.scanRecord.serviceData[mk]
                                    if (root.hash != null &&
                                        result.scanRecord.serviceData.containsKey(mk) &&
                                        root.hash.contentEquals(remoteroot)
                                    ) {
                                        LOG.v("luid $luid has unchanged merkle root ${remoteroot?.toHexString()}, ignoring")
                                        batchDisposables.remove(luid)
                                        return@flatMapCompletable Completable.complete()
                                    }

                                    LOG.w("luid $luid has merkle root ${remoteroot?.toHexString()}, attempting connection")

                                    if (leState.get().updateActive(luid)) {
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
                                            if (e is TransactionError) {
                                                leState.get().updateGone(e.luid, e)
                                            } else {
                                                leState.get().updateGone(luid, e)
                                            }
                                            if (e is RxJavaAssemblyException) {
                                                LOG.e(e.stacktrace())
                                            }
                                            e.printStackTrace()
                                        }
                                        .onErrorComplete()

                            }.observeOn(sched)
                                // .doOnDispose { scatterbrainScheduler.unpauseScan() }
                                .doFinally {
                                    //   scatterbrainScheduler.unpauseScan()
                                    connectLock.set(false)
                                    batchDisposables.remove(luid)
                                }
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


    /**
     * Terminates the batch handler for a given device
     * @param luid luid of the remote peer
     */
    fun killBatch(luid: UUID) {
        LOG.w("killBatch $luid")
        batchDisposables.clear()
       // scatterbrainScheduler.get().unpauseScan()
    }

    /**
     * Terminates all running tasks
     */
    fun killall() {
        for ((_, disp) in batchDisposables.entries) {
            disp.dispose()
        }
        batchDisposables.clear()
        dispose()
    }

    fun dispose() {
        disposable.getAndSet(null)?.dispose()
    }
}