package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Scatterbrain
import scatterbrain.Scatterbrain.MeshtasticErr
import scatterbrain.Scatterbrain.MeshtasticErrCode

@SbPacket(messageType = Scatterbrain.MessageType.MESHTASTIC_ERR)
class MeshtasticErrPacket(
    packet: MeshtasticErr
): ScatterSerializable<MeshtasticErr>(packet, Scatterbrain.MessageType.MESHTASTIC_ERR) {
    override fun validate(): Boolean {
        return true
    }

    constructor(code: MeshtasticErrCode): this(
        MeshtasticErr.newBuilder()
            .setCode(code)
            .build()
    )

    val code: MeshtasticErrCode
        get() = packet.code
}