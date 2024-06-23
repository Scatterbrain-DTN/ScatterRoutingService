package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import com.polidea.rxandroidble2.internal.RxBleLog
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import javax.inject.Inject
import javax.inject.Named

@GattServerConnectionScope
class ServerOperationQueueImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) callbackScheduler: Scheduler
) :
    ServerConnectionOperationQueue {
    val queue = OperationPriorityFifoBlockingQueue()

    init {
        Thread {
            while (true) {
                try {
                    val entry = queue.take()
                    /*
                             * Calling bluetooth calls before the previous one returns in a callback usually finishes with a failure
                             * status. Below a QueueSemaphore is passed to the RxBleCustomOperation and is meant to be released
                             * at appropriate time when the next operation should be able to start successfully.
                             */
                    val clientOperationSemaphore = QueueSemaphore()
                    entry.run(clientOperationSemaphore, callbackScheduler)
                    clientOperationSemaphore.awaitRelease()
                } catch (e: InterruptedException) {
                    RxBleLog.e(e, "Error while processing client operation queue")
                }
            }
        }.start()
    }

    override fun <T : Any> queue(operation: Operation<T>): Observable<T> {
        return Observable.create { tEmitter ->
            val entry = FIFORunnableEntry(
                operation,
                tEmitter
            )
            queue.add(entry)
        }
    }
}
