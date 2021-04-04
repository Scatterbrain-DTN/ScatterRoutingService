package net.ballmerlabs.uscatterbrain.network

import android.util.Log
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.ScatterProto.BlockData
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable.PacketType
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Wrapper class for protocol buffer blockdata message
 */
class BlockHeaderPacket private constructor(builder: Builder) : ScatterSerializable  {
    /**
     * Gets blockdata.
     *
     * @return the blockdata
     */
    private var blockdata: BlockData? = null
        private set

    /**
     * Gets hash list.
     *
     * @return the hash list
     */
    val hashList: List<ByteString>?

    /**
     * Gets from fingerprint.
     *
     * @return the from fingerprint
     */
    val fromFingerprint: ByteString?

    /**
     * Gets to fingerprint.
     *
     * @return the to fingerprint
     */
    val toFingerprint: ByteString?
    private val extension: String?

    /**
     * Get signature byte [ ].
     *
     * @return the byte [ ]
     */
    var signature: ByteArray?
        private set

    /**
     * Get application byte [ ].
     *
     * @return the byte [ ]
     */
    val application: ByteArray?

    /**
     * Gets session id.
     *
     * @return the session id
     */
    val sessionID: Int?
    var toDisk: Boolean
    var isEndOfStream: Boolean
        private set
    private val mBlocksize: Int
    val mime: String?
    var userFilename: String? = null
    override var luid: UUID? = null
        private set


    init {
        isEndOfStream = builder.endofstream
        hashList = builder.hashlist
        this.extension = builder.extensionVal
        signature = if (builder.sig == null) {
            ByteArray(Sign.ED25519_BYTES)
        } else {
            builder.sig
        }
        toFingerprint = if (builder.getmToFingerprint() != null) {
            ByteString.copyFrom(builder.getmToFingerprint())
        } else {
            ByteString.EMPTY
        }
        fromFingerprint = if (builder.getmFromFingerprint() != null) {
            ByteString.copyFrom(builder.getmFromFingerprint())
        } else {
            ByteString.EMPTY
        }
        application = builder.application
        sessionID = builder.sessionid
        toDisk = builder.toDisk
        mBlocksize = builder.blockSizeVal!!
        mime = builder.mime
        val b = BlockData.newBuilder()
        if (builder.filename == null) {
            userFilename = autogenFilename
            b.filenameGone = true
        } else {
            b.filenameVal = builder.filename
            userFilename = builder.filename
        }
        regenBlockData()
    }

    fun markEnd() {
        isEndOfStream = true
        regenBlockData()
    }

    private fun sumBytes(): ByteString {
        var messagebytes = ByteString.EMPTY
        messagebytes = messagebytes.concat(fromFingerprint)
        messagebytes = messagebytes.concat(toFingerprint)
        messagebytes = messagebytes.concat(ByteString.copyFrom(application))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(this.extension))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(mime))
        messagebytes = messagebytes.concat(ByteString.copyFromUtf8(userFilename))
        var td: Byte = 0
        if (toDisk) td = 1
        val toDiskBytes = ByteString.copyFrom(ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array())
        messagebytes = messagebytes.concat(toDiskBytes)
        for (hash in hashList!!) {
            messagebytes = messagebytes.concat(hash)
        }
        return messagebytes
    }

    /**
     * Verifyed 25519 boolean.
     *
     * @param pubkey the pubkey
     * @return the boolean
     */
    fun verifyed25519(pubkey: ByteArray): Boolean {
        if (pubkey.size != Sign.PUBLICKEYBYTES) return false
        val messagebytes = sumBytes()
        return LibsodiumInterface.sodium.crypto_sign_verify_detached(blockdata!!.sig.toByteArray(),
                messagebytes.toByteArray(),
                messagebytes.size().toLong(),
                pubkey) == 0
    }

    private fun regenBlockData() {
        if (isEndOfStream) {
            blockdata = BlockData.newBuilder()
                    .setEndofstream(true)
                    .build()
        } else {
            blockdata = BlockData.newBuilder()
                    .setApplicationBytes(ByteString.copyFrom(application))
                    .setFromFingerprint(fromFingerprint)
                    .setToFingerprint(toFingerprint)
                    .setTodisk(toDisk)
                    .setExtension(this.extension)
                    .addAllNexthashes(hashList)
                    .setSessionid(sessionID!!)
                    .setBlocksize(mBlocksize)
                    .setMime(mime)
                    .setEndofstream(isEndOfStream)
                    .setSig(ByteString.copyFrom(signature))
                    .build()
        }
    }

    /**
     * Sign ed 25519 boolean.
     *
     * @param secretkey the secretkey
     * @return the boolean
     */
    fun signEd25519(secretkey: ByteArray): Boolean {
        if (secretkey.size != Sign.SECRETKEYBYTES) return false
        val messagebytes = sumBytes()
        signature = ByteArray(Sign.ED25519_BYTES)
        val p = PointerByReference(Pointer.NULL).pointer
        return if (LibsodiumInterface.sodium.crypto_sign_detached(signature,
                        p, messagebytes.toByteArray(), messagebytes.size().toLong(), secretkey) == 0) {
            regenBlockData()
            true
        } else {
            false
        }
    }

    val autogenFilename: String
        get() {
            if (isEndOfStream) {
                return ""
            }

            val ext: String = ScatterbrainDatastore.getDefaultFileName(this) + "." +
                    ScatterbrainDatastore.sanitizeFilename(extension!!)
            Log.e("debug", "getAutogenFilename: $ext")
            return ext
        }

    override fun tagLuid(luid: UUID?) {
        this.luid = luid
    }

    override val bytes: ByteArray
        get() {
            val os = ByteArrayOutputStream()
            return try {
                CRCProtobuf.writeToCRC(blockdata!!, os)
                os.toByteArray()
            } catch (e: IOException) {
                byteArrayOf(0) //this should be unreachable
            }
        }

    override val byteString: ByteString
        get() = ByteString.copyFrom(bytes)

    override fun writeToStream(os: OutputStream): Completable {
        return Completable.fromAction { CRCProtobuf.writeToCRC(blockdata!!, os) }
    }

    override fun writeToStream(fragsize: Int): Flowable<ByteArray> {
        return Bytes.from(ByteArrayInputStream(bytes), fragsize)
    }

    override val type: PacketType
        get() = PacketType.TYPE_BLOCKHEADER

    /**
     * Gets the blocksize
     * @return int blocksize
     */
    val blockSize: Int
        get() = blockdata!!.blocksize

    /**
     * Gets hash.
     *
     * @param seqnum the seqnum
     * @return the hash
     */
    fun getHash(seqnum: Int): ByteString {
        return blockdata!!.getNexthashes(seqnum)
    }

    /**
     * Gets sig.
     *
     * @return the sig
     */
    val sig: ByteString
        get() = ByteString.copyFrom(signature)

    /**
     * Gets file extension
     * @return file extension
     */
    fun getExtension(): String {
        return ScatterbrainDatastore.sanitizeFilename(extension!!)
    }

    /**
     * The type Builder.
     */
    data class Builder(
            var toDisk: Boolean = false,
            var application: ByteArray? = null,
            var sessionid: Int? = null,
            var blockSizeVal: Int? = null,
            var mToFingerprint: ByteArray? = null,
            var mFromFingerprint: ByteArray? = null,
            var extensionVal: String = "",
            var hashlist: List<ByteString>? = null,
            var sig: ByteArray? = null,
            var filename: String? = null,
            var mime: String? = null,
            var endofstream: Boolean = false,

    ) {

        /**
         * Sets the fingerprint for the recipient.
         *
         * @param toFingerprint the to fingerprint
         * @return builder
         */
        fun setToFingerprint(toFingerprint: ByteArray?) = apply {
            mToFingerprint = toFingerprint
        }

        /**
         * Sets from fingerprint.
         *
         * @param fromFingerprint sets the fingerprint for the sender
         * @return builder
         */
        fun setFromFingerprint(fromFingerprint: ByteArray?) = apply {
            mFromFingerprint = fromFingerprint
        }

        /**
         * Sets application.
         *
         * @param application bytes for UTF encoded scatterbrain application string
         * @return builder
         */
        fun setApplication(application: ByteArray) = apply {
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

        fun setEndOfStream() = apply {
            endofstream = true
        }

        fun setFilename(filename: String?) = apply {
            this.filename = filename
        }

        /**
         * Build block header packet.
         *
         * @return the block header packet
         */
        fun build(): BlockHeaderPacket {
            if (!endofstream) {
                requireNotNull(hashlist) { "hashlist was null" }

                // fingerprints and application are required
                requireNotNull(application) { "application was null" }
                if (blockSizeVal!! <= 0) {
                    val e = IllegalArgumentException("blocksize not set")
                    e.printStackTrace()
                    throw e
                }
            }
            return BlockHeaderPacket(this)
        }

        /**
         * Gets to fingerprint.
         *
         * @return the to fingerprint
         */
        fun getmToFingerprint(): ByteArray? {
            return mToFingerprint
        }

        /**
         * Gets from fingerprint.
         *
         * @return the from fingerprint
         */
        fun getmFromFingerprint(): ByteArray? {
            return mFromFingerprint
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

        private fun builderFromIs(inputStream: InputStream) : Builder {
            val blockdata = CRCProtobuf.parseFromCRC(BlockData.parser(), inputStream)
            val builder = Builder()

            if (blockdata.endofstream) {
                return builder.setEndOfStream()
            } else {
                val filename: String? = if (blockdata.filenameCase == BlockData.FilenameCase.FILENAME_VAL) {
                    blockdata.filenameVal
                } else{
                    null
                }
                return builder.setApplication(blockdata!!.applicationBytes.toByteArray())
                        .setHashes(blockdata.nexthashesList)
                        .setFromFingerprint(blockdata.fromFingerprint.toByteArray())
                        .setToFingerprint(blockdata.toFingerprint.toByteArray())
                        .setSig(blockdata.sig.toByteArray())
                        .setToDisk(blockdata.todisk)
                        .setSessionID(blockdata.sessionid)
                        .setBlockSize(blockdata.blocksize)
                        .setExtension(blockdata.extension)
                        .setFilename(filename)
                        .setMime(blockdata.mime)
            }
        }

        /**
         * Parse from blockheader packet.
         *
         * @param is the is
         * @return the block header packet
         */
        fun parseFrom(`is`: InputStream): Single<BlockHeaderPacket> {
            return Single.fromCallable { BlockHeaderPacket(builderFromIs(`is`)) }
        }

        fun parseFrom(flowable: Observable<ByteArray>): Single<BlockHeaderPacket> {
            val observer = InputStreamObserver(4096) //TODO find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        fun parseFrom(flowable: Flowable<ByteArray>): Single<BlockHeaderPacket> {
            val observer = InputStreamFlowableSubscriber(4096) //TODO: find a way to calculate max size
            flowable.subscribe(observer)
            return parseFrom(observer).doFinally { observer.close() }
        }

        /**
         * New builder builder.
         *
         * @return the builder
         */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return Builder()
        }
    }
}