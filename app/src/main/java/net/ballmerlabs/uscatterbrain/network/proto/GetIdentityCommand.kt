package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import scatterbrain.Scatterbrain

import java.util.UUID
import net.ballmerlabs.scatterproto.*
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.GET_IDENTITY)
class GetIdentityCommand(
    packet: Desktop.GetIdentityCommand
): ScatterSerializable<Desktop.GetIdentityCommand>(packet, MessageType.GET_IDENTITY) {

    constructor(
        header: ApiHeader,
        id: UUID
    ): this(
        Desktop.GetIdentityCommand.newBuilder()
            .setIdentity(id.toProto())
            .setHeader(header.packet)
            .build()
    )

    val owned: Boolean = packet.owned

    val id: UUID? = if (packet.idCase == Desktop.GetIdentityCommand.IdCase.IDENTITY) packet.identity?.toUuid() else null

    override fun validate(): Boolean {
        return true
    }
}