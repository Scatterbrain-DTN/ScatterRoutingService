package net.ballmerlabs.uscatterbrain.network

import android.util.Log
import com.google.protobuf.ByteString
import net.ballmerlabs.uscatterbrain.ScatterProto.BlockData
import net.ballmerlabs.uscatterbrain.db.getDefaultFileName
import net.ballmerlabs.uscatterbrain.db.sanitizeFilename
import java.util.*
import kotlin.collections.ArrayList

/**
 * Wrapper class for protocol buffer blockdata message
 */
class BlockHeaderPacket(blockdata: BlockData) : ScatterSerializable<BlockData>(blockdata), Verifiable  {
    /**
     * Gets hash list.
     *
     * @return the hash list
     */
    val hashList
    get() = packet.nexthashesList

    override val hashes
        get() = hashList.map { v -> v.toByteArray() }.toTypedArray()


    /**
     * Gets from fingerprint.
     *
     * @return the from fingerprint
     */
    override val fromFingerprint: String?
        get() {
            val r = packet.fromFingerprint
            return if (r.isEmpty()) null else r
        }

    override val sendDate: Long
        get() = packet.sendDate

    /**
     * Gets to fingerprint.
     *
     * @return the to fingerprint
     */
    override val toFingerprint: String?
        get() {
            val r = packet.toFingerprint
            return if (r.isEmpty()) null else r
        }

    override val extension: String
        get() = sanitizeFilename(packet.extension)

    /**
     * Get signature byte [ ].
     *
     * @return the byte [ ]
     */
    override val signature: ByteArray?
        get() = if (fromFingerprint == null) null else packet.sig.toByteArray()

    /**
     * Get application byte [ ].
     *
     * @return the byte [ ]
     */
    override val application: String
        get() = packet.application

    /**
     * Gets session id.
     *
     * @return the session id
     */
    val sessionID: Int
        get() = packet.sessionid

    override val toDisk: Boolean
        get() = packet.todisk

    val isEndOfStream: Boolean
        get() = packet.endofstream

    private val mBlocksize: Int
        get() = packet.blocksize

    override val mime: String
        get() = packet.mime

    override val userFilename: String
    get() = packet.filename

    val autogenFilename: String
        get() {
            if (isEndOfStream) {
                return ""
            }

            val ext: String = getDefaultFileName(this) + "." + extension
            Log.e("debug", "getAutogenFilename: $ext")
            return ext
        }

    override val type: PacketType
        get() = PacketType.TYPE_BLOCKHEADER

    /**
     * Gets the blocksize
     * @return int blocksize
     */
    val blockSize: Int
        get() = packet.blocksize

    /**
     * Gets hash.
     *
     * @param seqnum the seqnum
     * @return the hash
     */
    fun getHash(seqnum: Int): ByteString {
        return packet.getNexthashes(seqnum)
    }

    /**
     * The type Builder.
     */
    data class Builder(
            var toDisk: Boolean = false,
            var application: String = "",
            var sessionid: Int = -1,
            var blockSizeVal: Int = -1,
            var mToFingerprint: String? = null,
            var mFromFingerprint: String? = null,
            var extensionVal: String = "",
            var hashlist: List<ByteString> = ArrayList(),
            var sig: ByteArray? = null,
            var filename: String = "",
            var mime: String = "",
            var endofstream: Boolean = false,
            var sendDate: Date = Date()
            ) {

        /**
         * Sets the fingerprint for the recipient.
         *
         * @param toFingerprint the to fingerprint
         * @return builder
         */
        fun setToFingerprint(toFingerprint: String?) = apply {
            mToFingerprint = toFingerprint
        }

        /**
         * Sets from fingerprint.
         *
         * @param fromFingerprint sets the fingerprint for the sender
         * @return builder
         */
        fun setFromFingerprint(fromFingerprint: String?) = apply {
            mFromFingerprint = fromFingerprint
        }

        /**
         * Sets application.
         *
         * @param application bytes for UTF encoded scatterbrain application string
         * @return builder
         */
        fun setApplication(application: String) = apply {
            this.application = application
        }
        /**
         * Sets to disk.
         *
         * @param toDisk whether to write this file to disk or attempt to store it in the database
         * @return builder
         */
        fun setToDisk(toDisk: Boolean) = apply {
            this.toDisk = toDisk
        }

        /**
         * Sets session id.
         *
         * @param sessionID the session id (used for upgrading between protocols)
         * @return builder
         */
        fun setSessionID(sessionID: Int) = apply {
            sessionid = sessionID
        }

        /**
         * Sets hashes.
         *
         * @param hashes list of hashes of following blocksequence packets.
         * @return builder
         */
        fun setHashes(hashes: List<ByteString>) = apply {
            hashlist = hashes
        }

        /**
         * Sets the file extension
         * @param ext: string file extension
         * @return builder
         */
        fun setExtension(ext: String) = apply {
            this.extensionVal = ext
        }

        /**
         * Sets blocksize
         * @param blockSize
         * @return builder
         */
        fun setBlockSize(blockSize: Int) = apply {
            this.blockSizeVal = blockSize
        }

        fun setSig(sig: ByteArray?) = apply {
            this.sig = sig
        }

        fun setMime(mime: String) = apply {
            this.mime = mime
        }

        fun setEndOfStream(value: Boolean) = apply {
            endofstream = value
        }

        fun setFilename(filename: String) = apply {
            this.filename = filename
        }

        fun setDate(date: Date) = apply {
            this.sendDate = date
        }

        /**
         * Build block header packet.
         *
         * @return the block header packet
         */
        fun build(): BlockHeaderPacket {
            if (!endofstream) {
                if (blockSizeVal <= 0) {
                    val e = IllegalArgumentException("blocksize not set")
                    e.printStackTrace()
                    throw e
                }
            }
            val packet = BlockData.newBuilder()
                    .setApplication(application)
                    .setFromFingerprint(mFromFingerprint?: "")
                    .setToFingerprint(mToFingerprint?: "")
                    .setTodisk(toDisk)
                    .setExtension(extensionVal)
                    .addAllNexthashes(hashlist)
                    .setSessionid(sessionid)
                    .setBlocksize(blockSizeVal)
                    .setSendDate(sendDate.time)
                    .setMime(mime)
                    .setEndofstream(endofstream)
                    .setSig(ByteString.copyFrom(sig?: byteArrayOf(0)))
                    .build()

            return BlockHeaderPacket(packet)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Builder

            if (toDisk != other.toDisk) return false
            if (application != null) {
                if (other.application == null) return false
                if (!application.contentEquals(other.application)) return false
            } else if (other.application != null) return false
            if (sessionid != other.sessionid) return false
            if (blockSizeVal != other.blockSizeVal) return false
            if (mToFingerprint != null) {
                if (other.mToFingerprint == null) return false
            } else if (other.mToFingerprint != null) return false
            if (mFromFingerprint != null) {
                if (other.mFromFingerprint == null) return false
            } else if (other.mFromFingerprint != null) return false
            if (extensionVal != other.extensionVal) return false
            if (hashlist != other.hashlist) return false
            if (sig != null) {
                if (other.sig == null) return false
                if (!sig.contentEquals(other.sig)) return false
            } else if (other.sig != null) return false
            if (filename != other.filename) return false
            if (mime != other.mime) return false
            if (endofstream != other.endofstream) return false

            return true
        }

        override fun hashCode(): Int {
            var result = toDisk.hashCode()
            result = 31 * result + (sessionid ?: 0)
            result = 31 * result + (blockSizeVal ?: 0)
            result = 31 * result + extensionVal.hashCode()
            result = 31 * result + (hashlist?.hashCode() ?: 0)
            result = 31 * result + (sig?.contentHashCode() ?: 0)
            result = 31 * result + (filename?.hashCode() ?: 0)
            result = 31 * result + (mime?.hashCode() ?: 0)
            result = 31 * result + endofstream.hashCode()
            return result
        }

        /**
         * Instantiates a new Builder.
         */
        init {
            sessionid = -1
            blockSizeVal = -1
            mime = "application/octet-stream"
        }
    }

    companion object {
        /**
         * New builder builder.
         *
         * @return the builder
         */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return Builder()
        }

        class Parser : ScatterSerializable.Companion.Parser<BlockData, BlockHeaderPacket>(BlockData.parser())
        fun parser(): Parser {
            return Parser()
        }
    }
}