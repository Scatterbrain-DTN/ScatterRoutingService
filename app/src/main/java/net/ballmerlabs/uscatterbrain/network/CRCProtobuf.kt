package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * wrapper for parser for protobuf messages that implements its own
 * length-prefix encoding and CRC checksumming.
 *
 * CRCs are a good idea because not all planned transports ensure
 * data integrity at the transport layer. It also catches my mistakes with
 * parsing ;)
 */
object CRCProtobuf {
    private const val MASK = 0xFFFFFFFFL
    private const val MESSAGE_SIZE_CAP = 1024 * 1024
    private fun bytes2long(payload: ByteArray): Long {
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)
        return (buffer.int.toLong() and MASK)
    }

    private fun longToByte(value: Long): ByteArray {
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(value.toInt())
        return buffer.array()
    }

    fun <T : MessageLite> parseFromCRC(parser: Parser<T>, inputStream: InputStream): T {
        val crc = ByteArray(4)
        val size = ByteArray(4)
        if (inputStream.read(size) != 4) {
            throw IOException("end of stream")
        }
        val s = ByteBuffer.wrap(size).order(ByteOrder.BIG_ENDIAN).int
        if (s > MESSAGE_SIZE_CAP) {
            throw IOException("invalid message size")
        }
        val co = CodedInputStream.newInstance(inputStream, s + 1)
        val messageBytes = co.readRawBytes(s)
        val message = parser.parseFrom(messageBytes)
        if (inputStream.read(crc) != crc.size) {
            throw IOException("end of stream")
        }
        val crc32 = CRC32()
        crc32.update(messageBytes)
        if (crc32.value != bytes2long(crc)) {
            throw IOException("invalid crc: " + crc32.value + " " + bytes2long(crc))
        }
        return message
    }

    fun writeToCRC(message: MessageLite, outputStream: OutputStream) {
        val out = message.toByteArray()
        outputStream.write(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(out.size).array()
        )
        val crc32 = CRC32()
        crc32.update(out)
        outputStream.write(out)
        outputStream.write(longToByte(crc32.value))
    }
}