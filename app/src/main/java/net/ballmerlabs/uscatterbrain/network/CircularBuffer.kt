package net.ballmerlabs.uscatterbrain.network

import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class CircularBuffer(private val writeBuffer: ByteBuffer) {
    private val readBuffer: ByteBuffer = writeBuffer.duplicate()
    fun size(): Int {
        return Math.floorMod(writeBuffer.position() - readBuffer.position(), writeBuffer.capacity())
    }

    fun get(): Byte {
        if (readBuffer.remaining() == 0) {
            readBuffer.position(0)
        }
        return readBuffer.get()
    }

    operator fun get(buf: ByteArray, offset: Int, length: Int) {
        if (length > size()) {
            throw BufferOverflowException()
        }
        var l = length
        val read = Math.min(length, readBuffer.remaining())
        readBuffer[buf, offset, read]
        l -= read
        if (l > 0) {
            readBuffer.position(0)
            readBuffer[buf, offset + read, l]
        }
    }

    fun put(buf: ByteArray, offset: Int, len: Int) {
        var l = Math.min(buf.size, len)
        if (l > remaining()) {
            throw BufferOverflowException()
        }
        val write = Math.min(l, writeBuffer.remaining())
        writeBuffer.put(buf, offset, write)
        l -= write
        if (l > 0) {
            writeBuffer.position(0)
            writeBuffer.put(buf, offset + write, l)
        }
    }

    fun remaining(): Int {
        return writeBuffer.capacity() - size()
    }

    fun skip(n: Long): Long {
        val skip = Math.min(remaining().toLong(), n).toInt()
        val p = readBuffer.position()
        readBuffer.position(readBuffer.position() + skip)
        return (readBuffer.position() - p).toLong()
    }

}