package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import io.reactivex.Observable

interface Operation<T> : Comparable<Operation<*>> {
    fun run(queueReleaseInterface: QueueReleaseInterface): Observable<T>

    fun definedPriority(): Priority
}