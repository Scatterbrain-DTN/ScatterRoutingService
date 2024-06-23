package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import io.reactivex.ObservableEmitter
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Cancellable
import java.util.concurrent.atomic.AtomicBoolean

class QueueReleasingEmitterWrapper<T : Any>(
    private val emitter: ObservableEmitter<T>,
    private val queueReleaseInterface: QueueReleaseInterface
) :
    Observer<T>, Cancellable {
    private val isEmitterCanceled = AtomicBoolean(false)

    init {
        emitter.setCancellable(this)
    }

    override fun onComplete() {
        queueReleaseInterface.release()
        emitter.onComplete()
    }

    override fun onError(e: Throwable) {
        queueReleaseInterface.release()
        emitter.tryOnError(e)
    }

    override fun onSubscribe(d: Disposable) {
    }

    override fun onNext(t: T) {
        emitter.onNext(t)
    }

    @Synchronized
    override fun cancel() {
        isEmitterCanceled.set(true)
    }

    @get:Synchronized
    val isWrappedEmitterUnsubscribed: Boolean
        get() = isEmitterCanceled.get()
}