package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.BlockSequence
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Wrapper class for protocol buffer BlockSequence message
 */
class BlockSequencePacket private constructor(builder: Builder) : ScatterSerializable {
    private val mSequenceNumber: Int
    private val mData: ByteString?
    private val mDataOnDisk: File?
    private val mBlockSequence: BlockSequence?
    var isNative = false
        private set
    override var luid: UUID? = null
        private set


    init {
        mSequenceNumber = builder.getmSequenceNumber()
        val d = builder.getmData()
        mDataOnDisk = builder.getmDataOnDisk()
        val tmpbuilder = BlockSequence.newBuilder()
        if (d != null) {
            tmpbuilder.dataContents = d
            mData = d
            isNative = false
        } else {
            isNative = true
            mData = ByteString.EMPTY
        }
        mBlockSequence = tmpbuilder.setSeqnum(mSequenceNumber)
                .build()
    }

    /**
     * Verify the hash of this message against its header
     *
     * @param bd the bd
     * @return boolean whether verification succeeded
     */
    fun verifyHash(bd: BlockHeaderPacket?): Boolean {
        val seqnum = ByteBuffer.allocate(4).putInt(mSequenceNumber).order(ByteOrder.BIG_ENDIAN).array()
        val data: ByteArray
        if (mBlockSequence!!.dataCase == BlockSequence.DataCase.DATA_CONTENTS) {
            data = mBlockSequence.dataContents.toByteArray()
            isNative = false
        } else {
            isNative = true
            data = ByteArray(0)
        }
        val testhash = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, testhash.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, data, data.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_final(state, testhash, testhash.size)
        return LibsodiumInterface.sodium.sodium_compare(testhash, bd!!.getHash(mSequenceNumber).toByteArray(), testhash.size) == 0
    }

    /**
     * Calculates the hash of this message
     *
     * @return the hash
     */
    fun calculateHash(): ByteArray {
        val hashbytes = ByteArray(GenericHash.BYTES)
        val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
        val seqnum = ByteBuffer.allocate(4).putInt(mSequenceNumber).order(ByteOrder.BIG_ENDIAN).array()
        LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, hashbytes.size)
        LibsodiumInterface.sodium.crypto_generichash_update(state, seqnum, seqnum.size.toLong())
        LibsodiumInterface.sodium.crypto_generichash_update(state, mData!!.toByteArray(), mData!!.size().toLong())
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

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(mBlockSequence, os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(mBlockSequence, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_BLOCKSEQUENCE

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    /**
     * Gets sequence number.
     *
     * @return the sequence number
     */
    fun getmSequenceNumber(): Int {
        return mSequenceNumber
    }

    /**
     * Gets data.
     *
     * @return the data
     */
    fun getmData(): ByteString? {
        return mData
    }

    /**
     * Gets data on disk.
     *
     * @return the data on disk
     */
    fun getmDataOnDisk(): File? {
        return mDataOnDisk
    }

    /**
     * Gets block sequence.
     *
     * @return the block sequence
     */
    fun getmBlockSequence(): BlockSequence? {
        return mBlockSequence
    }

    /**
     * Builder class for BlockSequencePacket
     */
    class Builder
    /**
     * Instantiates a new Builder.
     */
    {
        private var mSequenceNumber = 0
        private var mData: ByteString? = null
        private val mDataOnDisk: File? = null
        private val mOnDisk = false

        /**
         * Sets sequence number.
         *
         * @param sequenceNumber the sequence number
         * @return the sequence number
         */
        fun setSequenceNumber(sequenceNumber: Int): Builder {
            mSequenceNumber = sequenceNumber
            return this
        }

        /**
         * Sets data.
         *
         * @param data the data
         * @return the data
         */
        fun setData(data: ByteString?): Builder {
            mData = data
            return this
        }

        /**
         * Build block sequence packet.
         *
         * @return the block sequence packet
         */
        fun build(): BlockSequencePacket {
            return BlockSequencePacket(this)
        }

        /**
         * Gets sequence number.
         *
         * @return the sequence number
         */
        fun getmSequenceNumber(): Int {
            return mSequenceNumber
        }

        /**
         * Gets data.
         *
         * @return the data
         */
        fun getmData(): ByteString? {
            return mData
        }

        /**
         * Gets file to write packet to
         *
         * @return file object
         */
        fun getmDataOnDisk(): File? {
            return mDataOnDisk
        }
    }

    companion object {

        private fun builderFromIs(inputStream: InputStream) : Builder {
            val blocksequence = CRCProtobuf.parseFromCRC(BlockSequence.parser(), inputStream)
            val builder = Builder()
            if (blocksequence.dataCase == BlockSequence.DataCase.DATA_CONTENTS) {
                builder.setData(blocksequence.dataContents)
            } else {
                builder.setData(null)
            }
            builder.setSequenceNumber(blocksequence.seqnum)
            return builder
        }

        /**
         * Parse from block sequence packet.
         *
         * @param is the is
         * @return the block sequence packet
         */
        fun parseFrom(`is`: InputStream): Single<BlockSequencePacket> {
            return Single.fromCallable { BlockSequencePacket(builderFromIs(`is`)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<BlockSequencePacket> {
            val observer = InputStreamObserver(32768) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<BlockSequencePacket> {
            val observer = InputStreamFlowableSubscriber(32768) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

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