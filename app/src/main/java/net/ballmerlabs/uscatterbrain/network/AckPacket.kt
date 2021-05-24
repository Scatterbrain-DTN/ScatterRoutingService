package net.ballmerlabs.uscatterbrain.network

import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.Ack
import java.io.InputStream

/**
 * AckPacket is currently unused
 *
 * if it were used it would be used as a replacement for some
 * "optionalable" type messages. currently using a separate message
 * type for that is hard because protocol buffers don't have
 * simple polymorphism
 */
class AckPacket(packet: Ack)  : ScatterSerializable<Ack>(packet) {

    enum class Status {
        OK, ERR, FILE_EXISTS, PROTO_INVALID
    }
    
    override val type: PacketType
        get() = PacketType.TYPE_ACK
    
    val reason: String
        get() = if (packet.messageNull) {
            ""
        } else {
            packet.messageVal
        }

    val status: Status
        get() = proto2status(packet.status)

    data class Builder(
            var message: String? = null,
            var status: Status? = null,
    ) {

        fun setMessage(message: String?) = apply {
            this.message = message
        }

        fun setStatus(status: Status?) = apply {
            this.status = status
        }

        fun build(): AckPacket {
            requireNotNull(status) { "status should not be null" }
            val builder = Ack.newBuilder()
            if (message == null) {
                builder.messageNull = true
            } else {
                builder.messageVal = message
            }
            return AckPacket(
                    builder.setStatus(status2proto(status))
                    .build())
        }
    }

    companion object {
        fun parseFrom(inputStream: InputStream): Single<AckPacket> {
            return Single.fromCallable { AckPacket(parseFromCRC(Ack.parser(), inputStream)) }
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
        class Parser : ScatterSerializable.Companion.Parser<Ack, AckPacket>(Ack.parser())
        fun parser(): Parser {
            return Parser()
        }
    }
}