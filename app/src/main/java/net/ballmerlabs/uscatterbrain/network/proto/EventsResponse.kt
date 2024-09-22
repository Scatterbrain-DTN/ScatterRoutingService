package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain
import proto.Scatterbrain.SbEvents

@SbPacket(messageType = Scatterbrain.MessageType.DESKTOP_EVENTS)
class SbEventsResponse(
    packet: SbEvents
): ScatterSerializable<SbEvents>(packet, Scatterbrain.MessageType.DESKTOP_EVENTS) {

    val events: List<DesktopEvent>
        get() = packet.eventsList.map { v -> DesktopEvent(v) }

    constructor(vararg event: DesktopEvent): this(
        SbEvents.newBuilder()
            .addAllEvents(event.map { v -> v.packet })
            .build()
    )

    constructor(event: Collection<DesktopEvent>): this(
        SbEvents.newBuilder()
            .addAllEvents(event.map { v -> v.packet })
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}

