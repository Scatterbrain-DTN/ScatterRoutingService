package net.ballmerlabs.uscatterbrain.network

import io.reactivex.disposables.Disposable
import java.io.IOException
import java.io.InputStream
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
abstract class InputStreamCallback(BUF_CAPACITY: Int) : InputStream() {
    protected var throwable: Throwable? = null
    protected var closed = false
    protected var disposable: Disposable? = null
    private val buf = CircularBuffer(ByteBuffer.allocate(BUF_CAPACITY + 1))
    private val blockingEmptyLock = Semaphore(1, true)
    private var complete = false
    protected fun acceptBytes(buf: ByteArray) {
        if (buf.size >= this.buf.remaining()) {
            blockingEmptyLock.release()
            throw BufferOverflowException()
        }
        this.buf.put(buf, 0, buf.size)
        blockingEmptyLock.release()

    }

    fun size(): Int {
        return buf.size()
    }

    private operator fun get(result: ByteArray, offset: Int, len: Int): Int {
        if (closed) {
            throw IOException("closed")
        }
        return try {
            while (buf.size() < len && !complete) {
                blockingEmptyLock.acquire()
            }
            val l = len.coerceAtMost(buf.size())
            buf[result, offset, l]
            l
        } catch (ignored: BufferUnderflowException) {
            throw IOException("underflow")
        } catch (ignored: InterruptedException) {
            -1
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
        closed = true
        blockingEmptyLock.release()
        disposable?.dispose()
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        super.mark(readlimit)
    }

    @Synchronized
    override fun reset() {
        super.reset()
    }

    override fun markSupported(): Boolean {
        return false
    }

    override fun read(): Int {
        if (closed) {
            throw IOException("closed")
        }
        return try {
            while (buf.size() == 0 && !complete) {
                blockingEmptyLock.acquire()
            }
            val r = if (complete) {
                -1
            } else {
                buf.get().toInt()
            }
            r
        } catch (ignored: InterruptedException) {
            -1
        }
    }

}