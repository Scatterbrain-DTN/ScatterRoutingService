package net.ballmerlabs.uscatterbrain.network.proto


import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.desktop.SessionMessage
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType
import scatterbrain.Desktop.RespCode

@SbPacket(messageType = MessageType.UNIT_RESPONSE)
class UnitResponse(
    packet: Desktop.UnitResponse,
): ScatterSerializable<Desktop.UnitResponse>(packet, MessageType.UNIT_RESPONSE), SessionMessage {

    constructor(
        success: RespCode,
        message: String? = null,
    ): this (
        if (message == null)
            Desktop.UnitResponse.newBuilder()
            .setCode(success)
            .build()
        else
            Desktop.UnitResponse.newBuilder()
                .setCode(success)
                .setMessageCode(message)
                .build()
    )

    val message: String? = if(
        packet.unitresponseMaybeMessageCase == Desktop.UnitResponse.UnitresponseMaybeMessageCase.MESSAGE_CODE
        )
        packet.messageCode
    else
        null

    val respcode: RespCode = packet.code

    override val header: ApiHeader = ApiHeader(packet.header)

    override fun validate(): Boolean {
        return true
    }
}