package net.ballmerlabs.uscatterbrain.network

import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun floorDiv(x: Int, y: Int): Int {
    var r = x / y
    // if the signs are different and modulo not zero, round down
    if (x xor y < 0 && r * y != x) {
        r--
    }
    return r
}

private fun floorMod(x: Int, y: Int): Int {
    return x - floorDiv(x, y) * y
}

/**
 * this is a component of the system for parsing BLE indications
 * into protobuf messages. It is a zero-copy circular buffer for
 * temporarily storing streams of bytes
 */
class CircularBuffer(private val writeBuffer: ByteBuffer) {
    private val lock = ReentrantLock()
    private val readBuffer: ByteBuffer = writeBuffer.duplicate()
    fun size(): Int {
        return lock.withLock {
            floorMod(writeBuffer.position() - readBuffer.position(), writeBuffer.capacity())
        }
    }

    fun get(): Byte {
        return lock.withLock {
            if (readBuffer.remaining() == 0) {
                readBuffer.position(0)
            }
            readBuffer.get()
        }
    }

    operator fun get(buf: ByteArray, offset: Int, length: Int) {
        lock.withLock {
            if (length > size()) {
                throw BufferOverflowException()
            }
            var l = length
            val read = length.coerceAtMost(readBuffer.remaining())
            readBuffer[buf, offset, read]
            l -= read
            if (l > 0) {
                readBuffer.position(0)
                readBuffer[buf, offset + read, l]
            }
        }

    }

    fun put(buf: ByteArray, offset: Int, len: Int) {
        lock.withLock {
            var l = buf.size.coerceAtMost(len)
            if (l > remaining()) {
                throw BufferOverflowException()
            }
            val write = l.coerceAtMost(writeBuffer.remaining())
            writeBuffer.put(buf, offset, write)
            l -= write
            if (l > 0) {
                writeBuffer.position(0)
                writeBuffer.put(buf, offset + write, l)
            }
        }
    }

    fun remaining(): Int {
        return lock.withLock { writeBuffer.capacity() - size() }
    }

    fun skip(n: Long): Long {
        return lock.withLock {
            val skip = remaining().toLong().coerceAtMost(n).toInt()
            val p = readBuffer.position()
            readBuffer.position(readBuffer.position() + skip)
            (readBuffer.position() - p).toLong()
        }
    }

}