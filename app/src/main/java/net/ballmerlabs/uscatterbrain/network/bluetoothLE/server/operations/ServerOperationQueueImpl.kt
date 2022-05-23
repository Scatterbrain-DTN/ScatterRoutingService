package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import android.annotation.SuppressLint
import com.polidea.rxandroidble2.internal.operations.Operation
import com.polidea.rxandroidble2.internal.serialization.ClientOperationQueueImpl
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import javax.inject.Inject
import javax.inject.Named

@GattServerConnectionScope
class ServerOperationQueueImpl @Inject constructor(
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val scheduler: Scheduler
): ClientOperationQueueImpl(scheduler), GattServerOperationQueue {
    @SuppressLint("RestrictedApi")
    override fun <T : Any?> queue(operation: Operation<T>): Observable<T> {
        return super.queue(operation)
    }
}