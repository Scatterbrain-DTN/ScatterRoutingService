package net.ballmerlabs.uscatterbrain.db

import com.google.protobuf.ByteString
import com.goterl.lazycode.lazysodium.interfaces.GenericHash
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.entities.Hashes
import net.ballmerlabs.uscatterbrain.db.entities.HashlessScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.KeylessIdentity
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.regex.Pattern

const val DATABASE_NAME = "scatterdb"
const val DEFAULT_BLOCKSIZE = 1024 * 2
val FILE_SANITIZE: Pattern = Pattern.compile("^(\\s+.*|.*[\\\\/:\"?*|<>].*|.*\\s+|.*\\.)\$\n")
const val USER_FILES_PATH = "userFiles"
const val CACHE_FILES_PATH = "systemFiles"

class ACL(val packageName: String, val appsig: String)

class OpenFile(path: File, append: Boolean) : Closeable {
    val inputStream: FileInputStream
    private var mOs: FileOutputStream
    private val mFile: File = path
    private val mMode: ScatterbrainDatastore.WriteMode = ScatterbrainDatastore.WriteMode.OVERWRITE
    private var mLocked: Boolean

    @Throws(IOException::class)
    override fun close() {
        inputStream.close()
        mOs.close()
    }

    init {
        mOs = FileOutputStream(mFile, append)
        inputStream = FileInputStream(mFile)
        mLocked = false
    }
}

fun getDefaultFileNameFromHashes(hashes: List<Hashes>): String {
    return getDefaultFileName(HashlessScatterMessage.hashes2hash(hashes))
}

fun sanitizeFilename(name: String): String {
    return FILE_SANITIZE.matcher(name).replaceAll("-")
}


fun sigSize(message: Verifiable): Int {
    val tosize = message.toFingerprint?.size?: 0
    val fromsize = message.fromFingerprint?.size?: 0
    val applicationSize = message.application.encodeToByteArray().size //UTF-8
    val extSize = message.extension.encodeToByteArray().size
    val mimeSize = message.mime.encodeToByteArray().size
    val userFilenameSize = message.userFilename?.encodeToByteArray()?.size?: 0
    val toDiskSize = 1
    val hashSize = message.hashes.fold(0 ) { sum, it -> sum + it.size }
    return tosize + fromsize + applicationSize + extSize + mimeSize + userFilenameSize +
            toDiskSize + hashSize
}

fun sumBytes(message: Verifiable): ByteString {
    val buf = ByteBuffer.allocate(sigSize(message))
    buf.put(message.fromFingerprint?: byteArrayOf(0))
    buf.put(message.toFingerprint?: byteArrayOf(0))
    buf.put(message.application.encodeToByteArray())
    buf.put(message.extension.encodeToByteArray())
    buf.put(message.mime.encodeToByteArray())
    buf.put(message.userFilename?.encodeToByteArray()?: byteArrayOf(0))
    var td: Byte = 0
    if (message.toDisk) td = 1
    val toDiskBytes = ByteBuffer.allocate(1).order(ByteOrder.BIG_ENDIAN).put(td).array()
    buf.put(toDiskBytes)
    for (hash in message.hashes) {
        buf.put(hash)
    }
    return ByteString.copyFrom(buf)
}

/**
 * Verify ed25519 signature
 *
 * @param pubkey the pubkey
 * @return the boolean
 */
fun verifyed25519(pubkey: ByteArray, message: Verifiable): Boolean {
    if (pubkey.size != Sign.PUBLICKEYBYTES) return false
    val messagebytes = sumBytes(message)
    return LibsodiumInterface.sodium.crypto_sign_verify_detached(message.signature,
            messagebytes.toByteArray(),
            messagebytes.size().toLong(),
            pubkey) == 0
}


/**
 * Sign ed 25519 boolean.
 *
 * @param secretkey the secretkey
 * @return the boolean
 */
fun signEd25519(secretkey: ByteArray, message: Verifiable): Boolean {
    if (secretkey.size != Sign.SECRETKEYBYTES) return false
    val messagebytes = sumBytes(message)
    message.signature = ByteArray(Sign.ED25519_BYTES)
    val p = PointerByReference(Pointer.NULL).pointer
    return LibsodiumInterface.sodium.crypto_sign_detached(message.signature,
                    p, messagebytes.toByteArray(), messagebytes.size().toLong(), secretkey) == 0
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
        LibsodiumInterface.sodium.crypto_generichash_update(state, bytes.hash, bytes.hash.size.toLong())
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
    return getDefaultFileName(packet.hashList)
}

/**
 * interface for scatterbrain datastore
 */
interface ScatterbrainDatastore {
    fun insertMessagesSync(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable
    fun insertMessage(stream: BlockDataStream): Completable
    fun insertMessages(messages: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Completable
    fun insertMessageToRoom(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable
    fun getTopRandomMessages(
            count: Int,
            delareHashes: DeclareHashesPacket
    ): Observable<BlockDataStream>
    val allFiles: Observable<String>
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
    fun getIdentityKey(identity: String): Single<ApiIdentity.KeyPair>
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
    fun deleteIdentities(vararg fingerprint: String): Completable

    enum class WriteMode {
        APPEND, OVERWRITE
    }
}