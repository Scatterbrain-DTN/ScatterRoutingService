package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import com.polidea.rxandroidble2.internal.operations.Operation
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.GattServerConnectionScope
import javax.inject.Inject

interface GattServerOperationQueue {
    fun <T: Any?> queue(operation: Operation<T>): Observable<T>
}