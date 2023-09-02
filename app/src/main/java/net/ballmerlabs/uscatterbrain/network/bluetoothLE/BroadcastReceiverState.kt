package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.uscatterbrain.util.LOCKS
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastReceiverState @Inject constructor(
    val leState: LeState,
    val advertiser: Advertiser
) {
    private val log by scatterLog()
    private val disposable = AtomicReference<Disposable?>(null)
    private val clear = AtomicBoolean()
    private val batchCounter = AtomicInteger()
    private val batch = ConcurrentHashMap<ScanResult, Boolean>()
    val connectLock = AtomicBoolean()
    private val advertiseDisposable = AtomicReference<Disposable?>(null)
    var shouldScan = false
    val tlock = AtomicBoolean(false)
    init {
        log.e("state init")
    }

    fun clear(c: Boolean): Boolean {
        val out = clear.getAndSet(c)
        return !out && c
    }

    fun batch(scanResult: List<ScanResult>, count: Int = 5): Flowable<ScanResult> {
        return Flowable.defer {
            batch.putAll(scanResult.distinctBy { v -> v.bleDevice.macAddress }.map { r -> Pair(r, true) })
            val c = batchCounter.accumulateAndGet(count) { v, acc ->
                val next = v + 1
                if (next > acc) {
                    0
                } else {
                    next
                }
            }
            if (c < count) {
                Flowable.empty()
            } else if (batch.isNotEmpty()) {
                val out = batch.keys().toList()
                /*
                log.e("batch complete: ${
                    out.joinToString(", ") { v -> v.bleDevice.macAddress }
                }")
                 */
                batch.clear()
            //    out.forEach { v -> leState.updateActive(v) }
                if(tlock.getAndSet(true)) {
                    Flowable.empty()
                } else {
                    Flowable.fromIterable(out)
                        .distinct { v -> v.bleDevice.macAddress }
                }
            } else {
                Flowable.empty()
            }
        }
    }

    fun advertiseDisposable(disposable: Disposable) {
        advertiseDisposable.getAndSet(disposable)?.dispose()
    }

    fun addTask(func: () -> Completable) {
        val comp = CompletableSubject.create()
        if(disposable.compareAndSet(null, comp.subscribe(
                {},
                { err -> log.e("addTask failure $err") }
        ))) {
            func()
                .doFinally {
                    disposable.set(null)
                }
                .subscribe(comp)
        }
    }

    fun dispose() {
       disposable.get()?.dispose()
    }
}