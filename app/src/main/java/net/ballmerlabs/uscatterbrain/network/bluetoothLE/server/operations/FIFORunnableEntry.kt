package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import io.reactivex.ObservableEmitter
import io.reactivex.Observer
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import java.util.concurrent.atomic.AtomicLong

class FIFORunnableEntry<T : Any>(
    val operation: Operation<T>,
    val operationResultObserver: ObservableEmitter<T>
) : Comparable<FIFORunnableEntry<*>> {
    private val seqNum: Long

    init {
        seqNum = SEQUENCE.getAndIncrement()
    }

    override fun compareTo(other: FIFORunnableEntry<*>): Int {
        var res = operation.compareTo(other.operation)
        if (res == 0 && other.operation !== this.operation) {
            res = (if (seqNum < other.seqNum) -1 else 1)
        }
        return res
    }

    fun run(semaphore: QueueSemaphore, subscribeScheduler: Scheduler) {
        if (operationResultObserver.isDisposed) {
            semaphore.release()
            return
        }

        /*
         * In some implementations (i.e. Samsung Android 4.3) calling BluetoothDevice.connectGatt()
         * from thread other than main thread ends in connecting with status 133. It's safer to make bluetooth calls
         * on the main thread.
         */
        subscribeScheduler.scheduleDirect {
            operation.run(semaphore)
                .unsubscribeOn(subscribeScheduler)
                .subscribe(object : Observer<T> {
                    override fun onSubscribe(disposable: Disposable) {
                        /*
                                     * We end up overwriting a disposable that was set to the observer in order to remove operation from queue.
                                     * This is ok since at this moment the operation is taken out of the queue anyway.
                                     */
                        operationResultObserver.setDisposable(disposable)
                    }

                    override fun onNext(item: T) {
                        operationResultObserver.onNext(item)
                    }

                    override fun onError(e: Throwable) {
                        operationResultObserver.tryOnError(e)
                    }

                    override fun onComplete() {
                        operationResultObserver.onComplete()
                    }
                })
        }
    }

    companion object {
        private val SEQUENCE = AtomicLong(0)
    }
}
