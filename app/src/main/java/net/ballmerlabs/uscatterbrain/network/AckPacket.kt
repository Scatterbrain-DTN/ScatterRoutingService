package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.Ack
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.util.*

/**
 * AckPacket is currently unused
 *
 * if it were used it would be used as a replacement for some
 * "optionalable" type messages. currently using a separate message
 * type for that is hard because protocol buffers don't have
 * simple polymorphism
 */
class AckPacket private constructor(builder: Builder)  : ScatterSerializable {
    private val mAck: Ack?
    override var luid: UUID? = null
        private set

    enum class Status {
        OK, ERR, FILE_EXISTS, PROTO_INVALID
    }

    init {
        val b = Ack.newBuilder()
        if (builder.message == null) {
            b.messageNull = true
        } else {
            b.messageVal = builder.message
        }
        b.status = status2proto(builder.status)
        mAck = b.build()
    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mAck!!, os)
                os.toByteArray()
            } catch (ignored: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mAck!!, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_ACK

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    val reason: String
        get() = if (mAck!!.messageNull) {
            ""
        } else {
            mAck.messageVal
        }

    val status: Status
        get() = proto2status(mAck!!.status)

    class Builder {
        var message: String? = null
        var status: Status? = null
        fun setMessage(message: String?): Builder {
            this.message = message
            return this
        }

        fun setStatus(status: Status?): Builder {
            this.status = status
            return this
        }

        fun build(): AckPacket {
            requireNotNull(status) { "status should not be null" }
            return AckPacket(this)
        }
    }

    companion object {
        private fun builderFromIs(inputStream: InputStream) : Builder {
            val ack  = CRCProtobuf.parseFromCRC(Ack.parser(), inputStream)
            val builder = Builder()
            if (ack.messageNull) {
                builder.setMessage(null)
                builder.setStatus(null)
            } else {
                builder.setMessage(ack.messageVal)
                builder.setStatus(builder.status)
            }
            return builder
        }

        fun parseFrom(inputStream: InputStream): Single<AckPacket> {
            return Single.fromCallable { AckPacket(builderFromIs(inputStream)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<AckPacket> {
            val observer = InputStreamObserver(512) //TODO: find a way to calculateout  max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<AckPacket> {
            val observer = InputStreamFlowableSubscriber(512) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        private fun proto2status(status: Ack.Status): Status {
            return when (status) {
                Ack.Status.FILE_EXISTS -> {
                    Status.FILE_EXISTS
                }
                Ack.Status.ERR -> {
                    Status.ERR
                }
                Ack.Status.OK -> {
                    Status.OK
                }
                else -> {
                    Status.PROTO_INVALID
                }
            }
        }

        private fun status2proto(status: Status?): Ack.Status? {
            return when (status) {
                Status.OK -> {
                    Ack.Status.OK
                }
                Status.ERR -> {
                    Ack.Status.ERR
                }
                Status.FILE_EXISTS -> {
                    Ack.Status.FILE_EXISTS
                }
                else -> {
                    null
                }
            }
        }

        fun newBuilder(): Builder {
            return Builder()
        }
    }
}