package net.ballmerlabs.uscatterbrain

import io.reactivex.internal.schedulers.NonBlockingThread
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class ScatterbrainThreadFactory : AtomicLong(), ThreadFactory {
    override fun newThread(r: Runnable): Thread {
        val name = "ScatterbrainThread-" + incrementAndGet()
        val t: Thread = ScatterbrainNonblockingThread(r, name)
        t.priority = Thread.NORM_PRIORITY
        t.isDaemon = true
        return t
    }

    internal class ScatterbrainNonblockingThread(run: Runnable, name: String) : Thread(run, name), NonBlockingThread

    override fun toByte(): Byte {
        return get().toByte()
    }

    override fun toChar(): Char {
        return get().toChar()
    }

    override fun toShort(): Short {
        return get().toShort()
    }
}