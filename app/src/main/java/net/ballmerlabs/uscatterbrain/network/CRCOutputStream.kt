package net.ballmerlabs.uscatterbrain.network

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

class CRCOutputStream(outputStream: OutputStream) : BufferedOutputStream(outputStream) {
    val crc = CRC32()
    override fun write(p0: Int) {
        crc.update(p0)
        super.write(p0)
    }

    override fun write(b: ByteArray?) {
        crc.update(b)
        super.write(b)
    }

    override fun write(b: ByteArray?, off: Int, len: Int) {
        crc.update(b, off, len)
        super.write(b, off, len)
    }
}