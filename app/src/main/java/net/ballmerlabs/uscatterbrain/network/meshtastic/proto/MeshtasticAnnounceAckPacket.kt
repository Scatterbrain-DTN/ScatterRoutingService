package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import proto.Scatterbrain
import proto.Scatterbrain.MeshtasticAnnounceAck
import proto.Scatterbrain.MessageType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@SbPacket(messageType = Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE)
class MeshtasticAnnounceAckPacket(
    announceAck: MeshtasticAnnounceAck
): ScatterSerializable<MeshtasticAnnounceAck>(announceAck, MessageType.MESHTASTIC_ANNOUNCE_ACK) {
    override fun validate(): Boolean {
        return packet.merkleRoot.size() == LibsodiumInterface.MERKLE_HASH_SIZE
    }
    val remoteLuid: UUID = packet.luid.toUuid()

    val remoteMerkleHash: ByteArray = packet.merkleRoot.toByteArray()
}