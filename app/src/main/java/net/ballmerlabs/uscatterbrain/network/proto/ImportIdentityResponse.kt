package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopMessage
import proto.Scatterbrain
import proto.Scatterbrain.ImportIdentityResponse.FinalResponse
import proto.Scatterbrain.MessageType
import proto.Scatterbrain.RespCode
import proto.Scatterbrain.UUID

data class FinalResult(
    val handle: java.util.UUID,
    val identity: java.util.UUID
)

@SbPacket(messageType = MessageType.IMPORT_IDENTITY_RESPONSE)
class ImportIdentityResponse(
    packet: Scatterbrain.ImportIdentityResponse
): ScatterSerializable<Scatterbrain.ImportIdentityResponse>(packet, MessageType.IMPORT_IDENTITY_RESPONSE) {

    val header: ApiHeader = ApiHeader(packet.header)

    val code: RespCode = packet.code

    val name: FinalResult? = if (packet.stateCase == Scatterbrain.ImportIdentityResponse.StateCase.FINAL)
        FinalResult(
            handle = packet.final.handle.toUuid(),
            identity = packet.final.identity.toUuid()
        )
    else
        null

    val handle: java.util.UUID? = if (packet.stateCase == Scatterbrain.ImportIdentityResponse.StateCase.HANDLE)
        packet.handle.toUuid()
    else
        null

    constructor(
        result: FinalResult
    ) : this(
        Scatterbrain.ImportIdentityResponse.newBuilder()
            .setFinal(FinalResponse.newBuilder()
                .setIdentity(result.identity.toProto())
                .setHandle(result.handle.toProto()))
            .setCode(RespCode.OK)
            .build()
    )

    constructor(handle: java.util.UUID): this(
        Scatterbrain.ImportIdentityResponse.newBuilder()
            .setHandle(handle.toProto())
            .build()
    )

    constructor(code: RespCode) : this(
        Scatterbrain.ImportIdentityResponse.newBuilder()
            .setCode(code)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}