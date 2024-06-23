package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain
import net.ballmerlabs.scatterproto.*
import proto.Scatterbrain.MessageType

/**
 * wrapper class for DeclareHashes protobuf message. Used to avoid sending
 * duplicate messages to a remote peer
 * @property optout no hashes are sent, the remote peer should accept all messages
 * @property hashes list of "globalhash" values of Scatterbrain messages
 */
@SbPacket(messageType = MessageType.DECLARE_HASHES)
class DeclareHashesPacket(
    packet: Scatterbrain.DeclareHashes
) : ScatterSerializable<Scatterbrain.DeclareHashes>(packet, MessageType.DECLARE_HASHES) {

    val optout: Boolean
        get() = packet.optout

    val hashes: List<ByteArray> = packet.hashesList.map { p -> p.toByteArray() }

    override fun validate(): Boolean {
        return hashes.size <= MAX_DECLAREHASHES
    }

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
            return DeclareHashesPacket(
                Scatterbrain.DeclareHashes.newBuilder().addAllHashes(hashes).setOptout(optout)
                    //.setType(ScatterProto.MessageType.DECLARE_HASHES)
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