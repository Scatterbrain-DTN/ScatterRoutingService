package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.Box
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

@SbPacket(messageType = MessageType.PAIRING_ACK)
class PairingAck(
    packet: Scatterbrain.PairingAck,
) : ScatterSerializable<Scatterbrain.PairingAck>(packet, MessageType.PAIRING_ACK) {

    constructor(
        session: ApiHeader,
        pubkey: ByteArray
    ): this(Scatterbrain.PairingAck.newBuilder()
        .setPubkey(ByteString.copyFrom(pubkey))
        .setSession(session.packet)
        .build()
    )

    override fun validate(): Boolean {
        return packet.pubkey.size() == Box.PUBLICKEYBYTES
    }
}