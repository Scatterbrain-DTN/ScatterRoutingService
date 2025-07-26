package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.toProto
import net.ballmerlabs.scatterproto.toUuid
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import scatterbrain.Scatterbrain
import scatterbrain.Scatterbrain.MeshtasticAnnounce
import java.util.UUID

@SbPacket(messageType = Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE)
class MeshtasticAnnouncePacket(
    announce: MeshtasticAnnounce
): ScatterSerializable<MeshtasticAnnounce>(announce, Scatterbrain.MessageType.MESHTASTIC_ANNOUNCE) {
    override fun validate(): Boolean {
        return packet.merkleRoot.size() == LibsodiumInterface.MERKLE_HASH_SIZE
    }

    constructor(luid: UUID, root: ByteArray): this(
        MeshtasticAnnounce.newBuilder()
            .setLuid(luid.toProto())
            .setMerkleRoot(ByteString.copyFrom(root))
            .build()
    )

    val remoteLuid: UUID = packet.luid.toUuid()

    val remoteMerkleHash: ByteArray = packet.merkleRoot.toByteArray()
}