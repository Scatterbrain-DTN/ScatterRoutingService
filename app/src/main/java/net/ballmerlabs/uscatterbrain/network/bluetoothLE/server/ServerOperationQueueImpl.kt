package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import com.polidea.rxandroidble2.ServerConnectionScope
import com.polidea.rxandroidble2.internal.operations.Operation
import com.polidea.rxandroidble2.internal.serialization.OperationQueueBase
import com.polidea.rxandroidble2.internal.serialization.ServerOperationQueue
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import javax.inject.Inject
import javax.inject.Named

@ServerConnectionScope
class ServerOperationQueueImpl @Inject constructor(
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler
): OperationQueueBase(scheduler), ServerOperationQueue {
    override fun <T : Any?> queue(operation: Operation<T>?): Observable<T> {
        return super.queue(operation)
    }
}