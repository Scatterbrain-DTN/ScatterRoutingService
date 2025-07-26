package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import com.google.protobuf.ByteString
import io.reactivex.Flowable
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.uscatterbrain.db.entities.DbMessage
import net.ballmerlabs.uscatterbrain.network.meshtastic.MESHTASTIC_MAX_LEN
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.SeqLike
import net.ballmerlabs.uscatterbrain.network.proto.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.util.scatterLog
import scatterbrain.Meshtastic.MeshtasticStream
import scatterbrain.Scatterbrain.MessageType
import java.util.concurrent.TimeUnit

@SbPacket(messageType = MessageType.MESHTASTIC_STREAM)
class MeshtasticStreamPacket(
    packet: MeshtasticStream
): SeqLike<MeshtasticStream>(packet, MessageType.MESHTASTIC_STREAM) {

    val payload: ByteArray
        get() = packet.payload.toByteArray()

    override val seq: Int
        get() = packet.seq

    override val end: Boolean
        get() = packet.end

    override fun validate(): Boolean {
        return packet.serializedSize < MESHTASTIC_MAX_LEN
    }


    constructor(seq: Int, body: ByteArray, end: Boolean = false): this(
        MeshtasticStream.newBuilder()
            .setSeq(seq)
            .setEnd(end)
            .setPayload(ByteString.copyFrom(body))
            .build()
    )

    companion object {
        const val fragsize = MESHTASTIC_MAX_LEN-Int.SIZE_BYTES*3
        fun fromDbMessage(dbMessage: DbMessage, sequence: Flowable<BlockSequencePacket>): Flowable<MeshtasticStreamPacket> {
            val stream = WifiDirectRadioModule.BlockDataStream(dbMessage, sequence)
            return stream.headerPacket.writeToStream(fragsize)
                .flatMapPublisher { v -> v }
                .concatWith(stream.sequencePackets.concatMap { packet ->
                    packet.writeToStream(fragsize).flatMapPublisher { v -> v }
                })
                .zipWith(Flowable.interval(0, TimeUnit.SECONDS)) { v, seq ->
                    MeshtasticStreamPacket(seq.toInt(), v)
                }
        }

        fun fromStream(stream: WifiDirectRadioModule.BlockDataStream): Flowable<ByteArray> {
            val log by scatterLog()
            return stream.headerPacket
                .writeToStream(fragsize)
                .blockingGet()
                .doOnComplete { log.v("wrote meshtastic header packet") }
                .concatWith(stream.sequencePackets.concatMap { packet ->
                    log.v("writing sequence packet end=${packet.isEnd}")
                    packet.writeToStream(fragsize)
                        .blockingGet()
                        .doOnComplete { log.v("wrote meshtastic sequence packet") }
                })

        }
    }
}