package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.disposables.Disposable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastReceiverState @Inject constructor() {
    val transactionLock = AtomicBoolean(false)
    val disposable = AtomicReference<Disposable?>(null)
}