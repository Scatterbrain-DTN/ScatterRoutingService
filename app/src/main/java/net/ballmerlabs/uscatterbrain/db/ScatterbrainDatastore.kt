package net.ballmerlabs.uscatterbrain.db

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import io.reactivex.*
import io.reactivex.Observable
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.entities.Hashes
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import java.io.*
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.util.*
import java.util.regex.Pattern
import kotlin.jvm.Throws

interface ScatterbrainDatastore {
    /**
     * For internal use, synchronously inserts messages to database
     * @param message room entity for message to insert
     * @return primary keys of message inserted
     */
    fun insertMessagesSync(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable

    /**
     * Inserts a message stream (with header and blocksequence packets) from a network source
     * into both the database and filestore if applicable
     * @return completable for message insertion
     */
    fun insertMessage(stream: BlockDataStream): Completable

    /**
     * Asynchronously inserts a list of messages into the datastore, allows tracking result
     * via provided callback
     *
     * @param messages room entities to insert
     * @return future returning list of ids inserted
     */
    fun insertMessages(messages: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Completable

    /**
     * Asynchronously inserts a single message into the datastore, allows tracking result
     * via provided callback
     *
     * @param message room entity to insert
     * @return future returning id of row inserted
     */
    fun insertMessageToRoom(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable

    /**
     * gets a randomized list of messages from the datastore. Needs to be observed
     * to get async result
     *
     * @param count how many messages to retrieve
     * @return livedata representation of list of messages
     */
    fun getTopRandomMessages(
            count: Int,
            delareHashes: DeclareHashesPacket
    ): Observable<BlockDataStream>

    /**
     * gets a list of all the files in the datastore.
     * @return list of DiskFiles objects
     */
    val allFiles: Observable<String>

    /**
     * Retrieves a message by an identity room entity
     *
     * @param id room entity to search by
     * @return livedata representation of list of messages
     */
    fun getMessagesByIdentity(id: KeylessIdentity): Observable<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>
    fun insertIdentityPacket(identity: List<IdentityPacket>): Completable
    fun getIdentity(ids: List<Long>): Observable<IdentityPacket>
    fun getFileMetadataSync(path: File): Map<String, Serializable>
    fun insertAndHashLocalFile(path: File, blocksize: Int): Map<String, Serializable>
    fun getMessageByPath(path: String): Single<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>
    fun insertApiIdentity(identity: ApiIdentity): Completable
    fun insertApiIdentities(identities: List<Identity>): Completable
    fun getApiIdentityByFingerprint(fingerprint: String): ApiIdentity
    fun addACLs(identityFingerprint: String, packagename: String, appsig: String): Completable
    fun deleteACLs(identityFingerprint: String, packageName: String, appsig: String): Completable
    fun getIdentityKey(identity: String): Maybe<ApiIdentity.KeyPair>
    fun messageCount(): Int
    fun deleteByPath(path: File): Int
    fun clear()
    fun deleteFile(path: File): Completable
    fun isOpen(path: File): Boolean
    fun close(path: File): Boolean
    fun open(path: File): Single<OpenFile>
    fun insertFile(stream: BlockDataStream): Completable
    fun hashFile(path: File, blocksize: Int): Single<List<ByteString>>
    fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket>
    fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket>
    fun getFilePath(packet: BlockHeaderPacket): File
    val cacheDir: File
    val userDir: File
    fun getFileSize(path: File): Long
    val allIdentities: List<Identity>
    fun getApiMessages(application: String): List<ScatterMessage>
    fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket>
    fun getApiMessages(id: Long): ScatterMessage
    fun insertAndHashFileFromApi(message: ApiScatterMessage, blocksize: Int): Completable
    val declareHashesPacket: Single<DeclareHashesPacket>
    fun getACLs(identity: String): Single<MutableList<ACL>>
    enum class FileCallbackResult {
        ERR_FILE_EXISTS, ERR_FILE_NO_EXISTS, ERR_IO_EXCEPTION, ERR_PERMISSION_DENIED, ERR_FAILED, ERR_SUCCESS, ERR_INVALID_ARGUMENT
    }

    enum class WriteMode {
        APPEND, OVERWRITE
    }

    class ACL(val packageName: String, val appsig: String)

    class OpenFile(path: File, append: Boolean) : Closeable {
        val inputStream: FileInputStream
        private var mOs: FileOutputStream
        private val mFile: File
        private val mMode: WriteMode
        private var mLocked: Boolean
        fun lock() {
            mLocked = true
        }

        fun unlock() {
            mLocked = false
        }

        @Throws(IOException::class)
        override fun close() {
            inputStream.close()
            mOs.close()
        }

        fun setMode(append: Boolean): Boolean {
            try {
                mOs.close()
                mOs = FileOutputStream(mFile, append)
            } catch (e: IOException) {
                return false
            }
            return true
        }

        private fun reset(): Boolean {
            return if (mMode == WriteMode.APPEND) {
                setMode(true)
            } else {
                setMode(false)
            }
        }

        val outputStream: FileOutputStream
            get() = if (mLocked) {
                throw IllegalStateException("file locked")
            } else {
                mOs
            }

        init {
            mMode = WriteMode.OVERWRITE
            mFile = path
            mOs = FileOutputStream(mFile, append)
            inputStream = FileInputStream(mFile)
            mLocked = false
        }
    }

    companion object {
        fun getDefaultFileNameFromHashes(hashes: List<Hashes>): String {
            return getDefaultFileName(HashlessScatterMessage.hashes2hash(hashes))
        }

        fun sanitizeFilename(name: String): String {
            return FILE_SANITIZE.matcher(name).replaceAll("-")
        }

        fun getNoFilename(body: ByteArray): String {
            val outhash = ByteArray(GenericHash.BYTES)
            val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
            LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, outhash.size)
            LibsodiumInterface.sodium.crypto_generichash_update(state, body, body.size.toLong())
            LibsodiumInterface.sodium.crypto_generichash_final(state, outhash, outhash.size)
            val buf = ByteBuffer.wrap(outhash)
            //note: this only is safe because crypto_generichash_BYTES_MIN is 16
            return UUID(buf.long, buf.long).toString()
        }

        fun getGlobalHash(hashes: List<ByteString>): ByteArray {
            val outhash = ByteArray(GenericHash.BYTES)
            val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
            LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, outhash.size)
            for (bytes in hashes) {
                LibsodiumInterface.sodium.crypto_generichash_update(state, bytes.toByteArray(), bytes.size().toLong())
            }
            LibsodiumInterface.sodium.crypto_generichash_final(state, outhash, outhash.size)
            return outhash
        }

        fun getGlobalHashDb(hashes: List<Hashes>): ByteArray {
            val outhash = ByteArray(GenericHash.BYTES)
            val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
            LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, outhash.size)
            for (bytes in hashes) {
                LibsodiumInterface.sodium.crypto_generichash_update(state, bytes.hash, bytes.hash?.size?.toLong()!!)
            }
            LibsodiumInterface.sodium.crypto_generichash_final(state, outhash, outhash.size)
            return outhash
        }

        fun getDefaultFileName(hashes: List<ByteString>): String {
            val buf = ByteBuffer.wrap(getGlobalHash(hashes))
            //note: this only is safe because crypto_generichash_BYTES_MIN is 16
            return UUID(buf.long, buf.long).toString()
        }

        fun getDefaultFileName(packet: BlockHeaderPacket): String {
            return getDefaultFileName(packet.hashList!!)
        }

        const val DATABASE_NAME = "scatterdb"
        const val DEFAULT_BLOCKSIZE = 1024 * 2
        val FILE_SANITIZE = Pattern.compile("/^[\\w.-]+$/\n")
        const val USER_FILES_PATH = "userFiles"
        const val CACHE_FILES_PATH = "systemFiles"
    }
}