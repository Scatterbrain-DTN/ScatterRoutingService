package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import java.util.concurrent.PriorityBlockingQueue

class OperationPriorityFifoBlockingQueue {
    private val q = PriorityBlockingQueue<FIFORunnableEntry<*>>()

    fun add(fifoRunnableEntry: FIFORunnableEntry<*>) {
        q.add(fifoRunnableEntry)
    }

    @Throws(InterruptedException::class)
    fun take(): FIFORunnableEntry<*> {
        return q.take()
    }

    fun takeNow(): FIFORunnableEntry<*> {
        return q.poll()
    }

    val isEmpty: Boolean
        get() = q.isEmpty()

    fun remove(fifoRunnableEntry: FIFORunnableEntry<*>): Boolean {
        for (entry in q) {
            if (entry === fifoRunnableEntry) {
                return q.remove(entry)
            }
        }
        return false
    }
}