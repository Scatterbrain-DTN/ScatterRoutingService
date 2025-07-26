package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Scatterbrain.MeshtasticAckCode
import scatterbrain.Scatterbrain.MeshtasticAnnounceSynAck
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.MESHTASTIC_ANNOUNCE_SYNACK)
class MeshtasticAnnounceSynAckPacket(
    announceAck: MeshtasticAnnounceSynAck
): ScatterSerializable<MeshtasticAnnounceSynAck>(announceAck, MessageType.MESHTASTIC_ANNOUNCE_SYNACK) {
    override fun validate(): Boolean {
        return true
    }

    constructor(code: MeshtasticAckCode): this(MeshtasticAnnounceSynAck.newBuilder()
        .setCode(code)
        .build())

    val code: MeshtasticAckCode = packet.code
}