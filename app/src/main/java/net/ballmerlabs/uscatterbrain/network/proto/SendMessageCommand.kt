package net.ballmerlabs.uscatterbrain.network.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopMessage
import proto.Scatterbrain.MessageType
import proto.Scatterbrain.SendMessageCmd
import java.util.UUID

@SbPacket(messageType = MessageType.SEND_MESSAGE)
class SendMessageCommand(
    packet: SendMessageCmd
): ScatterSerializable<SendMessageCmd>(packet, MessageType.SEND_MESSAGE) {

    val identity: UUID? = if (packet.signIdentityCase == SendMessageCmd.SignIdentityCase.IDENTITY)
        packet.identity.toUuid()
    else
        null

    val data: List<DesktopMessage> = packet.messagesList.map { m -> DesktopMessage(m) }

    override fun validate(): Boolean {
        return true
    }
}