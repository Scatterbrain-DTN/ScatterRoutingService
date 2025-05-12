package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain.MeshtasticAckCode
import proto.Scatterbrain.MeshtasticAnnounceSynAck
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.MESHTASTIC_ANNOUNCE_SYNACK)
class MeshtasticAnnounceSynAckPacket(
    announceAck: MeshtasticAnnounceSynAck
): ScatterSerializable<MeshtasticAnnounceSynAck>(announceAck, MessageType.MESHTASTIC_ANNOUNCE_SYNACK) {
    override fun validate(): Boolean {
        return true
    }

    val code: MeshtasticAckCode = packet.code
}