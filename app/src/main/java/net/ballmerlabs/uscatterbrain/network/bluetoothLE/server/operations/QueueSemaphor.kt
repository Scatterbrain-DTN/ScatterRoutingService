package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations

import com.polidea.rxandroidble2.internal.RxBleLog
import java.util.concurrent.atomic.AtomicBoolean

class QueueSemaphore : QueueReleaseInterface,
    QueueAwaitReleaseInterface {
    private val isReleased = AtomicBoolean(false)

    @Synchronized
    @Throws(InterruptedException::class)
    override fun awaitRelease() {
        while (!isReleased.get()) {
            try {
                (this as Object).wait()
            } catch (e: InterruptedException) {
                if (!isReleased.get()) {
                    RxBleLog.w(
                        e, "Queue's awaitRelease() has been interrupted abruptly "
                                + "while it wasn't released by the release() method."
                    )
                }
            }
        }
    }

    @Synchronized
    override fun release() {
        if (isReleased.compareAndSet(false, true)) {
            (this as Object).notify()
        }
    }
}
