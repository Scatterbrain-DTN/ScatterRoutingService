package net.ballmerlabs.uscatterbrain.network.meshtastic.proto

import com.google.protobuf.kotlin.toByteString
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.uscatterbrain.db.entities.MerkleBundle
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.meshtastic.MESHTASTIC_MAX_LEN
import net.ballmerlabs.uscatterbrain.network.meshtastic.utils.SeqLike
import proto.Scatterbrain.MeshtasticMerkle
import proto.Scatterbrain.MessageType
import kotlin.math.floor
import kotlin.math.sqrt

@SbPacket(messageType = MessageType.MESHTASTIC_MERKLE)
class MeshtasticMerklePacket(
    packet: MeshtasticMerkle
): SeqLike<MeshtasticMerkle>(packet, MessageType.MESHTASTIC_MERKLE) {
    override fun validate(): Boolean {
        return packet.serializedSize <= MESHTASTIC_MAX_LEN && packet.hashesCount <= MESHTASTIC_MAX_HASHES
    }

    constructor(seq: Int, hashes: List<ByteArray>, end: Boolean = false): this(
        MeshtasticMerkle.newBuilder()
            .addAllHashes(hashes.map { v -> v.toByteString() })
            .setSeq(seq)
            .setEnd(end)
            .build()
    )

    override val end: Boolean
        get() = packet.end


    val hashes: List<ByteArray>
        get() = packet.hashesList.map { v -> v.toByteArray() }

    override val seq: Int
        get() = packet.seq

    companion object {
        fun fromHandshake(obs: Observable<MerkleBundle>, seq: Int): Single<MeshtasticMerklePacket> {
            return obs.take(MESHTASTIC_MAX_HASHES)
                .filter { v -> v.hash != null }
                .map { v -> v.hash!! }
                .toList()
                .map { hashes -> MeshtasticMerklePacket(seq, hashes) }
        }

        val MESHTASTIC_MAX_HASHES: Long = floor((MESHTASTIC_MAX_LEN - Int.SIZE_BYTES) / LibsodiumInterface.MERKLE_HASH_SIZE.toDouble()).toLong()
    }
}