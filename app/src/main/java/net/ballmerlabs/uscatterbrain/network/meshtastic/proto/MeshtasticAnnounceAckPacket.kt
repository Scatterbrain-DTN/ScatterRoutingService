package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import proto.Scatterbrain.MeshtasticAckCode
import proto.Scatterbrain.MeshtasticAnnounceAck
import proto.Scatterbrain.MessageType
import java.util.UUID

@SbPacket(messageType = MessageType.MESHTASTIC_ANNOUNCE_ACK)
class MeshtasticAnnounceAckPacket(
    announceAck: MeshtasticAnnounceAck
): ScatterSerializable<MeshtasticAnnounceAck>(announceAck, MessageType.MESHTASTIC_ANNOUNCE_ACK) {
    override fun validate(): Boolean {
        return packet.merkleRoot.size() == LibsodiumInterface.MERKLE_HASH_SIZE
    }

    constructor(remoteLuid: UUID, remoteMerkleHash: ByteArray, code: MeshtasticAckCode): this(
        announceAck = MeshtasticAnnounceAck.newBuilder()
            .setLuid(remoteLuid.toProto())
            .setMerkleRoot(ByteString.copyFrom(remoteMerkleHash))
            .setCode(code)
            .build()
    )

    val remoteLuid: UUID = packet.luid.toUuid()

    val code: MeshtasticAckCode = packet.code

    val remoteMerkleHash: ByteArray = packet.merkleRoot.toByteArray()
}