package net.ballmerlabs.uscatterbrain.network.proto
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_APPLICATION_NAME
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_REQUEST)
class PairingRequest(
    packet: Desktop.PairingRequest,
) : ScatterSerializable<Desktop.PairingRequest>(packet, MessageType.PAIRING_REQUEST) {
    override fun validate(): Boolean {
        return packet.name.length <= MAX_APPLICATION_NAME
    }
}