package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_APPLICATION_NAME
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_SYNACK)
class PairingSynAck(
    packet: Scatterbrain.PairingSynAck
): ScatterSerializable<Scatterbrain.PairingSynAck>(packet, MessageType.PAIRING_SYNACK) {
    val message: String
        get() = packet.message

    val success: Boolean = packet.success
    override fun validate(): Boolean {
        return packet.message.length <= MAX_APPLICATION_NAME
    }
}