package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import io.reactivex.Observable

interface ServerConnectionOperationQueue {
    /**
     * Function that queues an [Operation] for execution.
     * @param operation the operation to execute
     * @param <T> type of the operation values
     * @return the observable representing the operation execution
    </T> */
    fun <T: Any> queue(operation: Operation<T>): Observable<T>
}