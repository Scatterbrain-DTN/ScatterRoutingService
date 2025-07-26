package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import scatterbrain.Desktop
import scatterbrain.Scatterbrain
import scatterbrain.Scatterbrain.MessageType
import scatterbrain.Desktop.RespCode
import java.util.UUID

@SbPacket(messageType = MessageType.GENERATE_IDENTITY_RESPONSE)
class GenerateIdentityResponse(
    packet: Desktop.GenerateIdentityResponse
): ScatterSerializable<Desktop.GenerateIdentityResponse>(packet, MessageType.GENERATE_IDENTITY_RESPONSE) {

    constructor(
        identity: UUID
    ): this(
        Desktop.GenerateIdentityResponse.newBuilder()
            .setIdentity(identity.toProto())
            .setCode(RespCode.OK)
            .build()
    )

    constructor(
        code: RespCode
    ): this(
        Desktop.GenerateIdentityResponse.newBuilder()
            .setCode(code)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}