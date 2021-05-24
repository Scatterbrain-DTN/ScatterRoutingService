package net.ballmerlabs.uscatterbrain.network

import android.content.res.Resources
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.zip.CRC32

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

fun providesToValArray(provides: List<AdvertisePacket.Provides>?): List<Int> {
    val res: MutableList<Int> = ArrayList()
    for (p in provides!!) {
        res.add(p.`val`)
    }
    return res
}

fun valToProvidesArray(vals: List<Int>): List<AdvertisePacket.Provides> {
    val provides = ArrayList<AdvertisePacket.Provides>()
    for (i in vals) {
        for (p in AdvertisePacket.Provides.values()) {
            if (p.`val` == i) {
                provides.add(p)
            }
        }
    }
    return provides
}

fun providesToVal(provides: AdvertisePacket.Provides): Int {
    return provides.`val`
}

fun valToProvides(v: Int): AdvertisePacket.Provides {
    for (p in AdvertisePacket.Provides.values()) {
        if (p.`val` == v) {
            return p
        }
    }
    throw Resources.NotFoundException()
}

fun protoUUIDtoUUID(uuid: ScatterProto.UUID): UUID {
    return UUID(uuid.upper, uuid.lower)
}

fun protoUUIDfromUUID(uuid: UUID?): ScatterProto.UUID {
    return ScatterProto.UUID.newBuilder()
            .setLower(uuid!!.leastSignificantBits)
            .setUpper(uuid.mostSignificantBits)
            .build()
}

/**
 * base class for all protobuf messages
 */
abstract class ScatterSerializable<T : MessageLite>(
        val packet: T
) {
    enum class PacketType {
        TYPE_ACK, TYPE_BLOCKSEQUENCE, TYPE_BLOCKHEADER, TYPE_IDENTITY, TYPE_ADVERTISE, TYPE_UPGRADE, TYPE_ELECT_LEADER, TYPE_LUID, TYPE_DECLARE_HASHES
    }

    var luid: UUID? = null

    val bytes: ByteArray
        get() {
            val size = packet.serializedSize + Int.SIZE_BYTES * 2
            val buf = ByteBuffer.allocate(size)
            buf.order(ByteOrder.BIG_ENDIAN).putInt(packet.serializedSize).array()
            val crc32 = CRC32()
            crc32.update(buf.array(), 0, packet.serializedSize + Int.SIZE_BYTES)
            buf.put(longToByte(crc32.value))
            return buf.array()
        }

    val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { writeToCRC(packet, os) }
    }

    fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    abstract val type: PacketType


    companion object {
        abstract class Parser<T: MessageLite, V: ScatterSerializable<T>>(
                val parser: com.google.protobuf.Parser<T>
        )

        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V, T>,
                inputStream: InputStream
        ): Single<T> {
            return Single.fromCallable {
                val message = parseFromCRC(parser.parser, inputStream)
                T::class.java.getConstructor(V::class.java).newInstance(message)
            }
        }


        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V,T> ,
                observable: Observable<ByteArray>
        ): Single<T> {
            return Single.just(observable)
                    .flatMap { obs ->
                        val observer = InputStreamObserver(4096) //TODO: optimize buffer size
                        obs.subscribe(observer)
                        parseWrapperFromCRC(parser, observer)
                    }
        }

        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V,T> ,
                flowable: Flowable<ByteArray>
        ): Single<T> {
            return Single.just(flowable)
                    .flatMap { obs ->
                        val subscriber = InputStreamFlowableSubscriber(4096) //TODO: optimize buffer size
                        obs.subscribe(subscriber)
                        parseWrapperFromCRC(parser, subscriber)
                    }
        }

        fun <T : MessageLite> parseFromCRC(parser: com.google.protobuf.Parser<T>, inputStream: InputStream): T {
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
}