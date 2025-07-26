package net.ballmerlabs.uscatterbrain.network.proto

import com.goterl.lazysodium.interfaces.Box
import net.ballmerlabs.sbproto.SbPacket
import scatterbrain.Scatterbrain
import net.ballmerlabs.scatterproto.*
import scatterbrain.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_INITIATE)
class PairingInitiate(
    packet: Scatterbrain.PairingInitiate
) : ScatterSerializable<Scatterbrain.PairingInitiate>(packet, MessageType.PAIRING_INITIATE) {
    val pubkey: ByteArray = packet.pubkey.toByteArray()

    override fun validate(): Boolean {
        return packet.pubkey.size() == Box.PUBLICKEYBYTES
    }
}