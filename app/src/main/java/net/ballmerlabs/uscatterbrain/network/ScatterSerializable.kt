package net.ballmerlabs.uscatterbrain.network

import android.content.res.Resources
import android.util.Log
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.ScatterProto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

fun protoUUIDfromUUID(uuid: UUID): ScatterProto.UUID {
    return ScatterProto.UUID.newBuilder()
            .setLower(uuid.leastSignificantBits)
            .setUpper(uuid.mostSignificantBits)
            .build()
}

/**
 * base class for all protobuf messages
 * @property luid local identifier for sender
 * @property bytes serialized byte array of this packet, including CRC and size-prefix
 * @property byteString protobuf ByteString version of bytes
 * @property type type of this packet, must be overriden with correct type
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
            val os = ByteArrayOutputStream(packet.serializedSize)
            packet.writeDelimitedTo(os)
            return os.toByteArray()
        }

    val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    private fun writeToStreamBlocking(outputStream: OutputStream) {
        packet.writeDelimitedTo(outputStream)
    }

    /**
     * Serializes this packet to the given output stream
     * @param os outputstream to write to
     * @param scheduler rxjava2 scheduler to run on
     * @return Completable
     */
    fun writeToStream(os: OutputStream, scheduler: Scheduler): Completable {
        return Completable.fromAction { writeToStreamBlocking(os) }
            .subscribeOn(scheduler)

    }

    /**
     * Serializes this packet to an rxjava2 byte array flowable
     * @param fragsize maximum size of each byte array emitted by the flowable
     * @param scheduler rxjava2 scheduler to run on
     * @return Flowable emitting byte arrays with serialized message
     */
    fun writeToStream(fragsize: Int, scheduler: Scheduler): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
            .subscribeOn(scheduler)

    }

    /**
     * Sets the luid of the sending peer
     * @param luid luid of sending peer
     */
    fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    abstract val type: PacketType


    companion object {
        abstract class Parser<T: MessageLite, V: ScatterSerializable<T>>(
                val parser: com.google.protobuf.Parser<T>
        )

        /**
         * Parse a ScatterSerializable from an InputStream while validating it's CRC.
         * This function returns a non-generic type deriving from ScatterSerializable determined
         * by the parser class passed to this function
         * @param parser type of packet to parse
         * @param inputStream inputStream to parse from. This function reads until the end of the packet
         * or until an error is encountered
         * @param scheduler rxjava2 scheduler to run on
         * @return Single emitting ScatterSerializable derived type
         */
        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V, T>,
                inputStream: InputStream,
                scheduler: Scheduler
        ): Single<T> {
            return Single.fromCallable {
                val message = parseFromCRC(parser.parser, inputStream)
                T::class.java.getConstructor(V::class.java).newInstance(message)
            }
                .subscribeOn(scheduler)

        }

        /**
         * Parse a ScatterSerializable from an InputStream while validating it's CRC.
         * This function returns a non-generic type deriving from ScatterSerializable determined
         * by the parser class passed to this function
         * @param parser type of packet to parse
         * @param flowable byte array flowable to parse from. This function reads until the
         * end of the packet or until an error is encountered or the flowable calls onComplete
         * @param scheduler rxjava2 scheduler to run on
         * @return Single emitting ScatterSerializable derived type
         */
        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V,T> ,
                flowable: Flowable<ByteArray>,
                scheduler: Scheduler
        ): Single<T> {
            return Single.just(flowable)
                    .map { obs ->
                        Log.e("debug", "observable")
                        val subscriber = InputStreamFlowableSubscriber(11024) //TODO: optimize buffer size
                        obs.subscribe(subscriber)
                        val message = parseFromCRC(parser.parser, subscriber)
                        T::class.java.getConstructor(V::class.java).newInstance(message)
                    }
                .subscribeOn(scheduler)

        }

        /**
         * Parse a ScatterSerializable from an InputStream while validating it's CRC.
         * This function returns a non-generic type deriving from ScatterSerializable determined
         * by the parser class passed to this function
         * @param parser type of packet to parse
         * @param observable byte array observable to parse from. This function reads until the
         * end of the packet or until an error is encountered or the observable calls onComplete
         * @param scheduler rxjava2 scheduler to run on
         * @return Single emitting ScatterSerializable derived type
         */
        inline fun <reified T: ScatterSerializable<V>, reified V: MessageLite> parseWrapperFromCRC(
                parser: Parser<V,T> ,
                observable: Observable<ByteArray>,
                scheduler: Scheduler
        ): Single<T> {
            return Single.just(observable)
                    .map { obs ->
                        val subscriber = InputStreamObserver(1*1024*512) //TODO: optimize buffer size
                        obs.subscribe(subscriber)
                        val message = parseFromCRC(parser.parser, subscriber)
                        T::class.java.getConstructor(V::class.java).newInstance(message)
                    }
                .subscribeOn(scheduler)

        }

        /**
         * Parse an unwrapped raw protobuf message from an inputStream. Internal use only
         * @param parser parser
         * @param inputStream inputStream
         * @return protobuf message
         */
        fun <T : MessageLite> parseFromCRC(parser: com.google.protobuf.Parser<T>, inputStream: InputStream): T {
            return parser.parseDelimitedFrom(inputStream)
        }
    }
}