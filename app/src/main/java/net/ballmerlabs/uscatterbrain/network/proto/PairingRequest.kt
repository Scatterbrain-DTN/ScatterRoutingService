package net.ballmerlabs.uscatterbrain.network.proto
import net.ballmerlabs.sbproto.SbPacket
import scatterbrain.Scatterbrain
import net.ballmerlabs.scatterproto.*
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_REQUEST)
class PairingRequest(
    packet: Scatterbrain.PairingRequest,
) : ScatterSerializable<Scatterbrain.PairingRequest>(packet, MessageType.PAIRING_REQUEST) {
    override fun validate(): Boolean {
        return packet.name.length <= MAX_APPLICATION_NAME
    }
}