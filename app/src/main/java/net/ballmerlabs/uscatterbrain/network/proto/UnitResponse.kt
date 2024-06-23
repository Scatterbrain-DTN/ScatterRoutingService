package net.ballmerlabs.uscatterbrain.network.proto


import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.desktop.SessionMessage
import proto.Scatterbrain.MessageType
import proto.Scatterbrain.RespCode

@SbPacket(messageType = MessageType.UNIT_RESPONSE)
class UnitResponse(
    packet: Scatterbrain.UnitResponse,
): ScatterSerializable<Scatterbrain.UnitResponse>(packet, MessageType.UNIT_RESPONSE), SessionMessage {

    constructor(
        success: RespCode,
        message: String? = null,
    ): this (
        if (message == null)
            Scatterbrain.UnitResponse.newBuilder()
            .setCode(success)
            .build()
        else
            Scatterbrain.UnitResponse.newBuilder()
                .setCode(success)
                .setMessage(message)
                .build()
    )

    val message: String? = if(packet.maybeMessageCase == Scatterbrain.UnitResponse.MaybeMessageCase.MESSAGE)
        packet.message
    else
        null

    val respcode: RespCode = packet.code

    override val header: ApiHeader = ApiHeader(packet.header)

    override fun validate(): Boolean {
        return true
    }
}