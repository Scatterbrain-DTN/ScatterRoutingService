package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.desktop.SessionMessage
import proto.Scatterbrain
import proto.Scatterbrain.GetEvents

@SbPacket(messageType = Scatterbrain.MessageType.GET_EVENTS)
class GetEventsCommand(
    packet: GetEvents
): ScatterSerializable<GetEvents>(packet, Scatterbrain.MessageType.GET_EVENTS), SessionMessage {

    val count: Int? = if(packet.maybeCountCase == GetEvents.MaybeCountCase.COUNT)
        packet.count
    else
        null

    val block: Boolean = packet.block

    override val header: ApiHeader
        get() = ApiHeader(packet.header)

    override fun validate(): Boolean {
        return true
    }
}