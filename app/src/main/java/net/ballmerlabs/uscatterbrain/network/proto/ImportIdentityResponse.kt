package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import scatterbrain.Desktop
import scatterbrain.Scatterbrain
import scatterbrain.Desktop.ImportIdentityResponse.FinalResponse
import scatterbrain.Scatterbrain.MessageType
import scatterbrain.Desktop.RespCode

data class FinalResult(
    val handle: java.util.UUID,
    val identity: java.util.UUID
)

@SbPacket(messageType = MessageType.IMPORT_IDENTITY_RESPONSE)
class ImportIdentityResponse(
    packet: Desktop.ImportIdentityResponse
): ScatterSerializable<Desktop.ImportIdentityResponse>(packet, MessageType.IMPORT_IDENTITY_RESPONSE) {

    val header: ApiHeader = ApiHeader(packet.header)

    val code: RespCode = packet.code

    val name: FinalResult? = if (packet.stateCase == Desktop.ImportIdentityResponse.StateCase.FINAL)
        FinalResult(
            handle = packet.final.handle.toUuid(),
            identity = packet.final.identity.toUuid()
        )
    else
        null

    val handle: java.util.UUID? = if (packet.stateCase == Desktop.ImportIdentityResponse.StateCase.HANDLE)
        packet.handle.toUuid()
    else
        null

    constructor(
        result: FinalResult
    ) : this(
        Desktop.ImportIdentityResponse.newBuilder()
            .setFinal(FinalResponse.newBuilder()
                .setIdentity(result.identity.toProto())
                .setHandle(result.handle.toProto()))
            .setCode(RespCode.OK)
            .build()
    )

    constructor(handle: java.util.UUID): this(
        Desktop.ImportIdentityResponse.newBuilder()
            .setHandle(handle.toProto())
            .build()
    )

    constructor(code: RespCode) : this(
        Desktop.ImportIdentityResponse.newBuilder()
            .setCode(code)
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}