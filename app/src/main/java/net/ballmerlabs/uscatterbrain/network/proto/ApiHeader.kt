package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import java.util.UUID
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.API_HEADER)
class ApiHeader(
    packet: Scatterbrain.ApiHeader,
) : ScatterSerializable<Scatterbrain.ApiHeader>(packet, MessageType.API_HEADER) {
    val session: UUID = packet.session.toUuid()
    val stream: Int? = if (packet.streamCase == Scatterbrain.ApiHeader.StreamCase.STREAM_NOT_SET) null
        else packet.streamId
    constructor(
        session: UUID,
        stream: Int
    ): this(
        Scatterbrain.ApiHeader.newBuilder()
            .setSession(session.toProto())
            .setStreamId(stream)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}