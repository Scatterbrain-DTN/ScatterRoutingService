package net.ballmerlabs.uscatterbrain.network

import java.io.OutputStream
import java.util.zip.CRC32

class CRCOutputStream(private val outputStream: OutputStream) : OutputStream() {
    val crc = CRC32()
    override fun write(p0: Int) {
        crc.update(p0)
        outputStream.write(p0)
    }

    override fun write(b: ByteArray?) {
        crc.update(b)
        outputStream.write(b)
    }

    override fun write(b: ByteArray?, off: Int, len: Int) {
        crc.update(b, off, len)
        outputStream.write(b, off, len)
    }

    override fun close() {
        outputStream.close()
    }

    override fun flush() {
        outputStream.flush()
    }
}