package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain

import java.util.UUID
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.GET_IDENTITY)
class GetIdentityCommand(
    packet: Scatterbrain.GetIdentityCommand
): ScatterSerializable<Scatterbrain.GetIdentityCommand>(packet, MessageType.GET_IDENTITY) {

    constructor(
        header: ApiHeader,
        id: UUID
    ): this(
        Scatterbrain.GetIdentityCommand.newBuilder()
            .setIdentity(id.toProto())
            .setHeader(header.packet)
            .build()
    )

    val owned: Boolean = packet.owned

    val id: UUID? = if (packet.idCase == Scatterbrain.GetIdentityCommand.IdCase.IDENTITY) packet.identity?.toUuid() else null

    override fun validate(): Boolean {
        return true
    }
}