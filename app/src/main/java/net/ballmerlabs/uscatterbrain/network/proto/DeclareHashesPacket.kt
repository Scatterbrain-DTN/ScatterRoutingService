package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import net.ballmerlabs.scatterproto.MAX_DECLAREHASHES
import net.ballmerlabs.scatterproto.ScatterSerializable
import scatterbrain.Merkle
import scatterbrain.Scatterbrain
import scatterbrain.Merkle.DeclareHashesMode
import scatterbrain.Scatterbrain.MessageType

/**
 * wrapper class for DeclareHashes protobuf message. Used to avoid sending
 * duplicate messages to a remote peer
 * @property optout no hashes are sent, the remote peer should accept all messages
 * @property hashes list of "globalhash" values of Scatterbrain messages
 */
@SbPacket(messageType = MessageType.DECLARE_HASHES)
data class DeclareHashesPacket(
    val p: Merkle.DeclareHashes,
) : ScatterSerializable<Merkle.DeclareHashes>(p, MessageType.DECLARE_HASHES) {

    val optout: Boolean
        get() = packet.optout

    val hashes: List<ByteArray> = packet.hashesList.map { p -> p.toByteArray() }

    val mode: DeclareHashesMode = packet.mode

    val exists: Boolean = packet.exists

    override fun validate(): Boolean {
        return hashes.size <= MAX_DECLAREHASHES
    }

    data class Builder(
        var hashes: List<ByteString> = arrayListOf(),
        var optout: Boolean = false,
        var mode: DeclareHashesMode = DeclareHashesMode.NORMAL,
        var exists: Boolean = false,
    ) {
        fun setHashes(hashes: List<ByteString>) = apply {
            this.hashes = hashes
        }

        fun setHashesByte(hashes: List<ByteArray>) = apply {
            this.hashes = hashes.map { p -> ByteString.copyFrom(p) }
        }

        fun setMode(mode: DeclareHashesMode) = apply {
            this.mode = mode
        }

        fun optOut() = apply {
            optout = true
        }

        fun setExists(exists: Boolean) = apply {
            this.exists = exists
        }

        fun build(): DeclareHashesPacket {
            return DeclareHashesPacket(
                Merkle.DeclareHashes.newBuilder()
                    .addAllHashes(hashes)
                    .setOptout(optout)
                    .setExists(exists)
                    .setMode(mode)
                    .build()
            )
        }

    }

    companion object {
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}