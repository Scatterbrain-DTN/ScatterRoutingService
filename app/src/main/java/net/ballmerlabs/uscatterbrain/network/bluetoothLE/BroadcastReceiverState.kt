package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastReceiverState @Inject constructor() {
    private val log by scatterLog()
    private val disposable = AtomicReference(CompositeDisposable())
    private val advertiseDisposable = AtomicReference<Disposable?>(null)
    var shouldScan = false
    init {
        log.e("state init")
    }

    fun advertiseDisposable(disposable: Disposable) {
        advertiseDisposable.getAndSet(disposable)?.dispose()
    }

    fun addDisposable(disp: Disposable) {
        disposable.getAndUpdate { d ->
            val n = if (d.isDisposed) {
                CompositeDisposable()
            } else {
                d
            }
            n.add(disp)
            n
        }
    }

    fun dispose() {
        disposable.get().dispose()
    }
}