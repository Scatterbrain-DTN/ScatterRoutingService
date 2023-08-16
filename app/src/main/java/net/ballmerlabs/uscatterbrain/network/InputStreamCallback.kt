package net.ballmerlabs.uscatterbrain.network

import io.reactivex.disposables.Disposable
import java.io.IOException
import java.io.InputStream
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
abstract class InputStreamCallback(BUF_CAPACITY: Int) : InputStream() {
    protected var throwable: Throwable? = null
    protected var closed = false
    private val buf = CircularBuffer(ByteBuffer.allocate(BUF_CAPACITY + 1))
    private val blockingEmptyLock = Semaphore(1, true)
    private val lock = ReentrantLock()

    protected fun acceptBytes(buf: ByteArray) {
        lock.withLock {
            if (buf.size >= this.buf.remaining()) {
                blockingEmptyLock.release()
                throw BufferOverflowException()
            }
            this.buf.put(buf, 0, buf.size)
        }
        blockingEmptyLock.release()
    }

    fun size(): Int {
        return buf.size()
    }

    fun clear() {
        buf.clear()
    }

    private operator fun get(result: ByteArray, offset: Int, len: Int): Int {
        try {
            while (true) {
                if (buf.size() < len) {
                    blockingEmptyLock.acquire()
                }
                lock.withLock {
                    if (buf.size() >= len) {
                        if (closed) {
                            throw IOException("closed")
                        }
                        val l = len.coerceAtMost(buf.size())

                        buf[result, offset, l]
                        return l
                    }
                }
            }
        } catch (ignored: BufferUnderflowException) {
            throw IOException("underflow")
        } catch (ignored: InterruptedException) {
            return -1
        }
    }

    override fun read(b: ByteArray): Int {
        return get(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return get(b, off, len)
    }


    override fun skip(n: Long): Long {
        if (n >= Int.MAX_VALUE || n <= Int.MIN_VALUE) {
            throw IOException("index out of range")
        }

        return buf.skip(n)
    }


    override fun available(): Int {
        return buf.remaining()
    }


    override fun close() {
      //  closed = true
        blockingEmptyLock.release()
    }

    override fun markSupported(): Boolean {
        return false
    }


    override fun read(): Int {
        if (closed) {
            throw IOException("closed")
        }
        try {
            while (true) {
                if (buf.size() == 0) {
                    blockingEmptyLock.acquire()
                }
                lock.withLock {

                if (buf.size() > 0)
                    return buf.get().toInt()
                }
            }
        } catch (ignored: InterruptedException) {
            return -1
        }
    }

}