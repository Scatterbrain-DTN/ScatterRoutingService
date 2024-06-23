package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import proto.Scatterbrain
import proto.Scatterbrain.MessageType
import proto.Scatterbrain.RespCode
import java.util.UUID

@SbPacket(messageType = MessageType.GENERATE_IDENTITY_RESPONSE)
class GenerateIdentityResponse(
    packet: Scatterbrain.GenerateIdentityResponse
): ScatterSerializable<Scatterbrain.GenerateIdentityResponse>(packet, MessageType.GENERATE_IDENTITY_RESPONSE) {

    constructor(
        identity: UUID
    ): this(
        Scatterbrain.GenerateIdentityResponse.newBuilder()
            .setIdentity(identity.toProto())
            .setCode(RespCode.OK)
            .build()
    )

    constructor(
        code: RespCode
    ): this(
        Scatterbrain.GenerateIdentityResponse.newBuilder()
            .setCode(code)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}