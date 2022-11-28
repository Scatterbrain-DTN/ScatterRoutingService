package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastReceiverState @Inject constructor() {
    private val log by scatterLog()
    val transactionLock = AtomicBoolean(false)
    val disposable = AtomicReference<Disposable?>(null)
    var shouldScan = false
    init {
        log.e("state init")
    }
}