package net.ballmerlabs.scatterproto
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.MessageLite
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import proto.Scatterbrain
import proto.Scatterbrain.MessageType
import proto.Scatterbrain.TypePrefix
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

private const val MASK = 0xFFFFFFFFL
fun bytes2long(payload: ByteArray): Long {
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

fun providesToValArray(provides: List<Provides>?): List<Int> {
    val res: MutableList<Int> = ArrayList()
    for (p in provides!!) {
        res.add(p.`val`)
    }
    return res
}

fun valToProvidesArray(vals: List<Int>): List<Provides> {
    val provides = ArrayList<Provides>()
    for (i in vals) {
        for (p in Provides.values()) {
            if (p.`val` == i) {
                provides.add(p)
            }
        }
    }
    return provides
}

fun providesToVal(provides: Provides): Int {
    return provides.`val`
}

fun valToProvides(v: Int): Provides {
    for (p in Provides.values()) {
        if (p.`val` == v) {
            return p
        }
    }
    throw IllegalStateException("provides not found")
}


fun Scatterbrain.UUID.toUuid(): UUID {
    return UUID(upper, lower)
}

fun UUID.toProto(): Scatterbrain.UUID {
    return Scatterbrain.UUID.newBuilder().setLower(leastSignificantBits)
        .setUpper(mostSignificantBits).build()
}

/**
 * base class for all protobuf messages
 * @property luid local identifier for sender
 * @property bytes serialized byte array of this packet, including CRC and size-prefix
 * @property byteString protobuf ByteString version of bytes
 * @property type type of this packet, must be overriden with correct type
 */
abstract class ScatterSerializable<T : MessageLite>(
    val packet: T,
    val type: MessageType
) : Limits {
    var luid: UUID? = null

    val bytes: ByteArray
        get() {
            val type = Scatterbrain.TypePrefix.newBuilder().setType(type).build()

            val size =
                packet.serializedSize + type.serializedSize + Int.SIZE_BYTES + Int.SIZE_BYTES * 2
            val buf = ByteBuffer.allocate(size)

            buf.order(ByteOrder.BIG_ENDIAN).putInt(type.serializedSize)
            buf.order(ByteOrder.BIG_ENDIAN).putInt(packet.serializedSize)
            val crc32 = CRC32()
            val bytes = packet.toByteArray()
            val typebytes = type.toByteArray()
            crc32.update(
                ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putInt(type.serializedSize).array()
            )

            crc32.update(
                ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putInt(packet.serializedSize).array()
            )

            crc32.update(typebytes, 0, typebytes.size)
            crc32.update(bytes, 0, bytes.size)
            buf.put(typebytes)
            buf.put(bytes)
            buf.put(longToByte(crc32.value))
            return buf.array()
        }

    val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    private fun writeToStreamBlocking(outputStream: OutputStream) {
        val stream = CRCOutputStream(outputStream)

        val ts = Scatterbrain.TypePrefix.newBuilder().setType(type).build()

        stream.write(
            ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(ts.serializedSize).array()
        )
        stream.write(
            ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putInt(packet.serializedSize).array()
        )
        ts.writeTo(stream)
        packet.writeTo(stream)
        outputStream.write(longToByte(stream.crc.value))
        outputStream.flush()
    }

    /**
     * Serializes this packet to the given output stream
     * @param os outputstream to write to
     * @param scheduler rxjava2 scheduler to run on
     * @return Completable
     */
    fun writeToStream(os: OutputStream, scheduler: Scheduler): Maybe<Completable> {
        return Maybe.defer {
            if (validate()) {
                Maybe.just(Completable.fromAction { writeToStreamBlocking(os) }
                    .subscribeOn(scheduler))
            } else {
                Maybe.empty()
            }
        }
    }

    /**
     * Serializes this packet to an rxjava2 byte array flowable
     * @param fragsize maximum size of each byte array emitted by the flowable
     * @param scheduler rxjava2 scheduler to run on
     * @return Flowable emitting byte arrays with serialized message
     */
    fun writeToStream(fragsize: Int, scheduler: Scheduler): Maybe<Flowable<ByteArray>> {
        return Maybe.defer {
            if (validate()) {
                Maybe.just(
                    Bytes.from(ByteArrayInputStream(bytes), fragsize).subscribeOn(scheduler)
                )
            } else {
                Maybe.empty()
            }
        }
    }

    /**
     * Serializes this packet to an rxjava2 byte array flowable
     * @param fragsize maximum size of each byte array emitted by the flowable
     * @return Flowable emitting byte arrays with serialized message
     */
    fun writeToStream(fragsize: Int): Maybe<Flowable<ByteArray>> {
        return Maybe.defer {
            if (validate()) {
                Maybe.just(Bytes.from(ByteArrayInputStream(bytes), fragsize))
            } else {
                Maybe.empty()
            }
        }
    }

    /**
     * Sets the luid of the sending peer
     * @param luid luid of sending peer
     */
    fun tagLuid(luid: UUID?) {
        this.luid = luid
    }



    companion object {
        abstract class Parser<T : MessageLite, V : ScatterSerializable<T>>(
            val parser: com.google.protobuf.Parser<T>, val type: MessageType
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
        inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> parseWrapperFromCRC(
            parser: Parser<V, T>, inputStream: InputStream, scheduler: Scheduler
        ): Single<T> {
            return Single.fromCallable {
                val message = parseFromCRC(parser, inputStream)
                T::class.java.getConstructor(V::class.java).newInstance(message)
            }.subscribeOn(scheduler)

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
        inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> parseWrapperFromCRC(
            parser: Parser<V, T>, flowable: Flowable<ByteArray>, scheduler: Scheduler
        ): Single<T> {
            return Single.just(flowable).map { obs ->
                    val subscriber =
                        InputStreamFlowableSubscriber(MESSAGE_SIZE_CAP)
                    obs.subscribe(subscriber)
                    val message = parseFromCRC(parser, subscriber)
                    T::class.java.getConstructor(V::class.java).newInstance(message)
                }.subscribeOn(scheduler)
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
        inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> parseWrapperFromCRC(
            parser: Parser<V, T>, observable: Observable<ByteArray>, scheduler: Scheduler
        ): Single<T> {
            return Single.just(observable).map { obs ->
                    val subscriber =
                        InputStreamObserver(MESSAGE_SIZE_CAP)
                    obs.subscribe(subscriber)
                    val message = parseFromCRC(parser, subscriber)
                    T::class.java.getConstructor(V::class.java).newInstance(message)
                }.subscribeOn(scheduler)
        }

        /**
         * Parse an unwrapped raw protobuf message from an inputStream. Internal use only
         * @param parser parser
         * @param inputStream inputStream
         * @return protobuf message
         */
        fun <T : MessageLite, U : ScatterSerializable<T>> parseFromCRC(
            parser: Parser<T, U>, inputStream: InputStream
        ): T {
            val crc = ByteArray(Int.SIZE_BYTES)
            val size = ByteArray(Int.SIZE_BYTES)
            val typesize = ByteArray(Int.SIZE_BYTES)
            if (inputStream.read(typesize) != Int.SIZE_BYTES) {
                throw IOException("end of stream")
            }
            if (inputStream.read(size) != Int.SIZE_BYTES) {
                throw IOException("end of stream")
            }

            val s = ByteBuffer.wrap(size).order(ByteOrder.BIG_ENDIAN).int
            val s2 = ByteBuffer.wrap(typesize).order(ByteOrder.BIG_ENDIAN).int
            if (s > MESSAGE_SIZE_CAP) {
                throw MessageSizeException()
            }
            if (s2 > BLOCK_SIZE_CAP) {
                throw MessageSizeException()
            }
            val co = CodedInputStream.newInstance(inputStream, s + 1)
            val typeBytes = co.readRawBytes(s2)
            val type = TypePrefix.parseFrom(typeBytes)
            val pt = type.type
            if (pt != parser.type) {
                throw InvalidPacketException(
                    type = pt, expected = parser.type
                )
            }

            val messageBytes = co.readRawBytes(s)
            val message = parser.parser.parseFrom(messageBytes)
            if (inputStream.read(crc) != crc.size) {
                throw IOException("end of stream")
            }
            val crc32 = CRC32()
            crc32.update(typesize)
            crc32.update(size)
            crc32.update(typeBytes)
            crc32.update(messageBytes)
            if (crc32.value != bytes2long(crc)) {
                throw IOException("invalid crc: " + crc32.value + " " + bytes2long(crc))
            }
            return message
        }

        var parsers: MutableMap<MessageType, Parser<GeneratedMessageLite<*, *>, *>> = mutableMapOf()


        data class TypedPacket(
            val packet: GeneratedMessageLite<*, *>,
            val type: MessageType
        ) {
            inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> get(
                parser: Parser<V, T>
            ): T? {
                return if (parser.type == type)
                    T::class.java.getConstructor(V::class.java).newInstance(packet)
                else
                    null

            }

            inline fun <reified T : ScatterSerializable<V>, reified V : MessageLite> get(): T {
                return T::class.java.getConstructor(V::class.java).newInstance(packet)
            }
        }
    }
}

class InvalidPacketException(
    val type: MessageType, val expected: MessageType
) : Throwable("invalid packet type $type expected $expected")
