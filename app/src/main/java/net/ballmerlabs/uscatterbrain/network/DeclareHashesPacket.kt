package net.ballmerlabs.uscatterbrain.network

import com.google.protobuf.ByteString
import net.ballmerlabs.uscatterbrain.ScatterProto.DeclareHashes

/**
 * wrapper class for DeclareHashes protobuf message
 */
class DeclareHashesPacket(packet: DeclareHashes) : ScatterSerializable<DeclareHashes>(packet) {

    val optout: Boolean
        get() = packet.optout

    val hashes: List<ByteArray>
        get() = packet.hashesList.map { p -> p.toByteArray() }

    override val type: PacketType
        get() = PacketType.TYPE_DECLARE_HASHES

    data class Builder(
            var hashes: List<ByteString> = arrayListOf(),
            var optout: Boolean = false,
    ) {
        fun setHashes(hashes: List<ByteString>) = apply {
            this.hashes = hashes
        }

        fun setHashesByte(hashes: List<ByteArray>) = apply {
            this.hashes = hashes.map { p -> ByteString.copyFrom(p) }
        }

        fun optOut() = apply {
            optout = true
        }

        fun build(): DeclareHashesPacket {
            return DeclareHashesPacket(DeclareHashes.newBuilder()
                    .addAllHashes(hashes)
                    .setOptout(optout)
                    .build())
        }

    }

    companion object {
        fun newBuilder(): Builder {
            return Builder()
        }

        class Parser : ScatterSerializable.Companion.Parser<DeclareHashes, DeclareHashesPacket>(DeclareHashes.parser())
        fun parser(): Parser {
            return Parser()
        }
    }
}