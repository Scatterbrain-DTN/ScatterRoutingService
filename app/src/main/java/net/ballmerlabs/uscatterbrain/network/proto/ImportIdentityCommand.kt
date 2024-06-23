package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toUuid
import proto.Scatterbrain
import java.util.UUID

@SbPacket(messageType = Scatterbrain.MessageType.IMPORT_IDENTITY)
class ImportIdentityCommand(
    packet: Scatterbrain.ImportIdentityCommand
): ScatterSerializable<Scatterbrain.ImportIdentityCommand>(packet, Scatterbrain.MessageType.IMPORT_IDENTITY) {

    val header: ApiHeader = ApiHeader(packet.header)

    val handle: UUID? = if (packet.maybeHandleCase == Scatterbrain.ImportIdentityCommand.MaybeHandleCase.HANDLE)
        packet.handle.toUuid()
    else
        null

    override fun validate(): Boolean {
        return true
    }
}