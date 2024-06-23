package net.ballmerlabs.uscatterbrain.network.proto

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import net.ballmerlabs.sbproto.SbPacket
import proto.Scatterbrain.BlockSequence
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import proto.Scatterbrain.MessageType

/**
 * Wrapper class for protocol buffer BlockSequence message
 * @property data payload for this packet
 * @property sequenceNum number of this packet in a stream
 * @property isNative honestly I have no idea why I added this or what this does, ignore it until I remember
 * @property isEnd is this packet the last in a stream
 */
@SbPacket(messageType = MessageType.BLOCK_SEQUENCE)
class BlockSequencePacket(
    packet: BlockSequence
) : ScatterSerializable<BlockSequence>(packet, MessageType.BLOCK_SEQUENCE) {

    val data: ByteArray
        get() = if (isNative) {
        ByteArray(0)
    } else {
        packet.dataContents.toByteArray()
    }

    val sequenceNum: Int
    get() = packet.seqnum

    val isNative: Boolean
    get() = packet.dataCase == BlockSequence.DataCase.DATA_NATIVE

    val isEnd: Boolean
    get() = packet.end

    override fun validate(): Boolean {
        return data.size <= BLOCK_SIZE_CAP
    }

    /**
     * Verify the hash of this message against its header
     *
     * @param bd the bd
     * @return boolean whether verification succeeded
     */
    fun verifyHash(bd: BlockHeaderPacket): Boolean {
        val seqnum = ByteBuffer.allocate(4).putInt(sequenceNum).order(ByteOrder.BIG_ENDIAN).array()
        val testhash = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, testhash.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, data, data.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_final(state, testhash, testhash.size)
        return LibsodiumInterface.sodium.sodium_compare(testhash, bd.getHash(sequenceNum).toByteArray(), testhash.size) == 0
    }

    /**
     * Calculates the hash of this message
     *
     * @return the hash
     */
    fun calculateHash(): ByteArray {
        val hashbytes = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        val seqnum = ByteBuffer.allocate(4).putInt(sequenceNum).order(ByteOrder.BIG_ENDIAN).array()
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, hashbytes.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, data, data.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_final(state, hashbytes, hashbytes.size)
        return hashbytes
    }

    /**
     * Calculates the hash of this message
     *
     * @return hash as ByteString
     */
    fun calculateHashByteString(): ByteString {
        return ByteString.copyFrom(calculateHash())
    }

    /**
     * Builder class for BlockSequencePacket
     */
    data class Builder(
            var sequenceNumber: Int = 0,
            var data: ByteString? = null,
            val dataOnDisk: File? = null,
            var onDisk: Boolean = false,
            var end: Boolean = false
    )
    /**
     * Instantiates a new Builder.
     */
    {

        /**
         * Sets sequence number.
         *
         * @param sequenceNumber the sequence number
         * @return the sequence number
         */
        fun setSequenceNumber(sequenceNumber: Int) = apply {
            this.sequenceNumber = sequenceNumber
        }

        /**
         * Sets data.
         *
         * @param data the data
         * @return the data
         */
        fun setData(data: ByteString?) = apply {
            this.data = data
        }

        /**
         * Sets end of stream
         * @param end
         */
        fun setEnd(end: Boolean) = apply {
            this.end = end
        }

        /**
         * Build block sequence packet.
         *
         * @return the block sequence packet
         */
        fun build(): BlockSequencePacket {
            val builder = BlockSequence.newBuilder()
            if (data != null) {
                builder.dataContents = data
            } else {
                builder.dataNative = true
            }
            builder.end = this.end
            return BlockSequencePacket(builder.setSeqnum(sequenceNumber)
                //.setType(ScatterProto.MessageType.BLOCK_SEQUENCE)
                .build())
        }
    }

    companion object {
        /**
         * New builder builder.
         *
         * @return the builder
         */
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}