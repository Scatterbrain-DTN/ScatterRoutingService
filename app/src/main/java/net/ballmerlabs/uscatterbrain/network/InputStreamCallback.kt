package net.ballmerlabs.uscatterbrain.network

import io.reactivex.disposables.Disposable
import java.io.IOException
import java.io.InputStream
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import kotlin.jvm.Throws

/**
 * This is a somewhat hacky brige between RxJava2 streams and a classic
 * inputstream. It buffers any data observed as an observer and
 * replays it when read from as an inputstream.
 */
abstract class InputStreamCallback(capacity: Int) : InputStream() {
    protected var throwable: Throwable? = null
    protected var closed = false
    protected var disposable: Disposable? = null
    private val buf: CircularBuffer
    private val BUF_CAPACITY: Int = capacity
    private val lock = java.lang.Object()
    private val readLock = Semaphore(1, true)
    private var complete = false
    protected fun acceptBytes(`val`: ByteArray) {
        if (`val`.size >= buf.remaining()) {
            throw BufferOverflowException()
        }
        buf.put(`val`, 0, `val`.size)
        readLock.release()
    }

    fun size(): Int {
        return buf.size()
    }

    protected fun complete() {
        synchronized(lock) {
            complete = true
            lock.notifyAll()
        }
    }

    @Throws(IOException::class)
    private operator fun get(result: ByteArray, offset: Int, len: Int): Int {
        if (throwable != null) {
            throwable!!.printStackTrace()
        }
        if (closed) {
            throw IOException("closed")
        }
        synchronized(lock) {
            return try {
                while (buf.size() < len && !complete) {
                    readLock.acquire()
                }
                val l = Math.min(len, buf.size())
                buf[result, offset, l]
                l
            } catch (ignored: BufferUnderflowException) {
                throw IOException("underflow")
            } catch (ignored: InterruptedException) {
                -1
            }
        }
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        return get(b, 0, b.size)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return get(b, off, len)
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        synchronized(lock) {
            if (n >= Int.MAX_VALUE || n <= Int.MIN_VALUE) {
                throw IOException("index out of range")
            }
            val skip: Long = 0
            return buf.skip(n)
        }
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return buf.remaining()
    }

    @Throws(IOException::class)
    override fun close() {
        closed = true
        readLock.release()
        if (disposable != null) {
            disposable!!.dispose()
        }
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        super.mark(readlimit)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        super.reset()
    }

    override fun markSupported(): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (closed) {
            throw IOException("closed")
        }
        synchronized(lock) {
            return try {
                while (buf.size() == 0 && !complete) {
                    readLock.acquire()
                }
                if (complete) {
                    -1
                } else {
                    buf.get().toInt()
                }
            } catch (ignored: InterruptedException) {
                -1
            }
        }
    }

    init {
        buf = CircularBuffer(ByteBuffer.allocate(BUF_CAPACITY + 1))
    }
}