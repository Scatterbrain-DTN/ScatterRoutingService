package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.GENERATE_IDENTITY)
class GenerateIdentityCommand(
    packet: Scatterbrain.GenerateIdentityCommand
): ScatterSerializable<Scatterbrain.GenerateIdentityCommand>(packet, MessageType.GENERATE_IDENTITY) {

    val header: ApiHeader = ApiHeader(packet.header)

    val name: String = packet.name

    override fun validate(): Boolean {
        return true
    }
}