package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.os.DeadObjectException
import com.polidea.rxandroidble2.exceptions.BleException
import com.polidea.rxandroidble2.internal.RxBleLog
import io.reactivex.Observable
import io.reactivex.ObservableEmitter

abstract class QueueOperation<T: Any> : Operation<T> {
    /**
     * A function that returns this operation as an Observable.
     * When the returned observable is subscribed, this operation will be scheduled
     * to be run on the main thread. When appropriate the call to run() will be executed.
     * This operation is expected to call releaseRadio() at appropriate point after the run() was called.
     */
    override fun run(queueReleaseInterface: QueueReleaseInterface): Observable<T> {
        return Observable.create { emitter ->
            try {
                protectedRun(emitter, queueReleaseInterface)
            } catch (deadObjectException: DeadObjectException) {
                emitter.tryOnError(provideException(deadObjectException)!!)
                RxBleLog.e(
                    deadObjectException,
                    "QueueOperation terminated with a DeadObjectException"
                )
            } catch (throwable: Throwable) {
                emitter.tryOnError(throwable)
                RxBleLog.e(throwable, "QueueOperation terminated with an unexpected exception")
            }
        }
    }

    /**
     * This method must be overridden in a concrete operation implementations and should contain specific operation logic.
     *
     * Implementations should call emitter methods to inform the outside world about emissions of `onNext()`/`onError()`/`onCompleted()`.
     * Implementations must call [QueueReleaseInterface.release] at appropriate point to release the queue for any other operations
     * that are queued.
     *
     * If the emitter is cancelled, a responsibility of the operation is to call [QueueReleaseInterface.release]. The radio
     * should be released as soon as the operation decides it won't interact with the [android.bluetooth.BluetoothGatt] anymore and
     * subsequent operations will be able to start. Check usage of [QueueReleasingEmitterWrapper] for convenience.
     *
     * @param emitter the emitter to be called in order to inform the caller about the output of a particular run of the operation
     * @param queueReleaseInterface the queue release interface to release the queue when ready
     */
    @Throws(Throwable::class)
    protected abstract fun protectedRun(
        emitter: ObservableEmitter<T>,
        queueReleaseInterface: QueueReleaseInterface
    )

    /**
     * This function will be overridden in concrete operation implementations to provide an exception with needed context
     *
     * @param deadObjectException the cause for the exception
     */
    protected abstract fun provideException(deadObjectException: DeadObjectException): BleException?

    /**
     * A function returning the priority of this operation
     *
     * @return the priority of this operation
     */
    override fun definedPriority(): Priority {
        return Priority.NORMAL
    }

    /**
     * The function for determining which position in Bluetooth Radio's Priority Blocking Queue
     * this operation should take
     *
     * @param another another operation
     * @return the comparison result
     */
    override fun compareTo(another: Operation<*>): Int {
        return another.definedPriority().priority - definedPriority().priority
    }
}