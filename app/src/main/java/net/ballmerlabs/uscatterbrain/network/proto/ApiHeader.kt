package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType
import java.util.UUID

@SbPacket(messageType = MessageType.API_HEADER)
class ApiHeader(
    packet: Desktop.ApiHeader,
) : ScatterSerializable<Desktop.ApiHeader>(packet, MessageType.API_HEADER) {
    val session: UUID = packet.session.toUuid()
    val stream: Int? = if (packet.streamCase == Desktop.ApiHeader.StreamCase.STREAM_NOT_SET) null
        else packet.streamId
    constructor(
        session: UUID,
        stream: Int
    ): this(
        Desktop.ApiHeader.newBuilder()
            .setSession(session.toProto())
            .setStreamId(stream)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}