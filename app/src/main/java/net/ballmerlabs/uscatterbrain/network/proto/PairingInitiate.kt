package net.ballmerlabs.uscatterbrain.network.proto

import com.goterl.lazysodium.interfaces.Box
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Desktop
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_INITIATE)
class PairingInitiate(
    packet: Desktop.PairingInitiate
) : ScatterSerializable<Desktop.PairingInitiate>(packet, MessageType.PAIRING_INITIATE) {
    val pubkey: ByteArray = packet.pubkey.toByteArray()

    override fun validate(): Boolean {
        return packet.pubkey.size() == Box.PUBLICKEYBYTES
    }
}