package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import proto.Scatterbrain
import proto.Scatterbrain.Events

@SbPacket(messageType = Scatterbrain.MessageType.DESKTOP_EVENTS)
class EventsResponse(
    packet: Events
): ScatterSerializable<Events>(packet, Scatterbrain.MessageType.DESKTOP_EVENTS) {

    val events: List<DesktopEvent>
        get() = packet.eventsList.map { v -> DesktopEvent(v) }

    constructor(vararg event: DesktopEvent): this(
        Events.newBuilder()
            .addAllEvents(event.map { v -> v.packet })
            .build()
    )

    constructor(event: Collection<DesktopEvent>): this(
        Events.newBuilder()
            .addAllEvents(event.map { v -> v.packet })
            .build()
    )

    override fun validate(): Boolean {
        return true
    }
}

