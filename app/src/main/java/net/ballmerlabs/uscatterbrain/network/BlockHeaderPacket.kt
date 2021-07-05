package net.ballmerlabs.uscatterbrain.network

import android.util.Log
import com.google.protobuf.ByteString
import net.ballmerlabs.uscatterbrain.ScatterProto.BlockData
import net.ballmerlabs.uscatterbrain.db.getDefaultFileName
import net.ballmerlabs.uscatterbrain.db.isValidFilename
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
    val hashList: List<ByteArray>
    get() = packet.nexthashesList.map { v -> v.toByteArray() }

    override val hashes
        get() = hashList.toTypedArray()


    /**
     * Gets from fingerprint.
     *
     * @return the from fingerprint
     */
    override val fromFingerprint: List<UUID>
        get() = packet.fromFingerprintList.map { f -> protoUUIDtoUUID(f) }

    override val sendDate: Long
        get() = packet.sendDate

    /**
     * Gets to fingerprint.
     *
     * @return the to fingerprint
     */
    override val toFingerprint: List<UUID>
        get() = packet.toFingerprintList.map { f -> protoUUIDtoUUID(f)}

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

    val isValidFilename: Boolean
    get() = isValidFilename(packet.filename)

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
            private  var toDisk: Boolean = false,
            private var application: String = "",
            private var sessionid: Int = -1,
            private var mToFingerprint: ArrayList<UUID> = arrayListOf(),
            private var mFromFingerprint: ArrayList<UUID> = arrayListOf(),
            private var extensionVal: String = "",
            private var hashlist: List<ByteString> = ArrayList(),
            private var sig: ByteArray? = null,
            private var filename: String = "",
            private var mime: String = "",
            private var endofstream: Boolean = false,
            private var sendDate: Date = Date()
            ) {

        /**
         * Sets the fingerprint for the recipient.
         *
         * @param toFingerprint the to fingerprint
         * @return builder
         */
        fun setToFingerprint(toFingerprint: UUID?) = apply {
            if (toFingerprint != null) {
                mToFingerprint.add(toFingerprint)
            }
        }

        /**
         * Sets from fingerprint.
         *
         * @param fromFingerprint sets the fingerprint for the sender
         * @return builder
         */
        fun setFromFingerprint(fromFingerprint: UUID?) = apply {
            if (fromFingerprint != null) {
                mFromFingerprint.add(fromFingerprint)
            }
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
            this.extensionVal = sanitizeFilename(ext)
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
            this.filename = sanitizeFilename(filename)
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
            val packet = BlockData.newBuilder()
                    .setApplication(application)
                    .addAllFromFingerprint(mFromFingerprint.map { u -> protoUUIDfromUUID(u) })
                    .addAllToFingerprint(mToFingerprint.map { u -> protoUUIDfromUUID(u) })
                    .setTodisk(toDisk)
                    .setExtension(extensionVal)
                    .addAllNexthashes(hashlist)
                    .setSessionid(sessionid)
                    .setSendDate(sendDate.time)
                    .setMime(mime)
                    .setTtl(0)
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
            if (!application.contentEquals(other.application)) return false
            if (sessionid != other.sessionid) return false

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
            result = 31 * result + sessionid
            result = 31 * result + extensionVal.hashCode()
            result = 31 * result + hashlist.hashCode()
            result = 31 * result + (sig?.contentHashCode() ?: 0)
            result = 31 * result + filename.hashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + endofstream.hashCode()
            return result
        }

        /**
         * Instantiates a new Builder.
         */
        init {
            sessionid = -1
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