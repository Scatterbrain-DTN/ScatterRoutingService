package net.ballmerlabs.uscatterbrain.db

import android.util.Log
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
import kotlin.collections.ArrayList

const val DATABASE_NAME = "scatterdb"
const val DEFAULT_BLOCKSIZE = 1024 * 512
val FILE_SANITIZE: Pattern = Pattern.compile("^[a-zA-Z0-9_()-]*$")
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
    return getDefaultFileNameDb(hashes)
}

fun sanitizeFilename(name: String): String {
    return if (FILE_SANITIZE.matcher(name).matches()) {
        name
    } else {
        UUID.randomUUID().toString()
    }
}


fun isValidFilename(name: String): Boolean {
    return FILE_SANITIZE.matcher(name).matches()
}


fun sigSize(message: Verifiable): Int {
    val tosize = message.toFingerprint.size * Long.SIZE_BYTES * 2
    val fromsize = message.fromFingerprint.size * Long.SIZE_BYTES * 2
    val applicationSize = message.application.encodeToByteArray().size //UTF-8
    val extSize = message.extension.encodeToByteArray().size
    val mimeSize = message.mime.encodeToByteArray().size
    val userFilenameSize = message.userFilename.encodeToByteArray().size
    val toDiskSize = 1
    val hashSize = message.hashes.fold(0 ) { sum, it -> sum + it.size }
    return tosize + fromsize + applicationSize + extSize + mimeSize + userFilenameSize +
            toDiskSize + hashSize
}

fun sumBytes(message: Verifiable): ByteString {
    val buf = ByteBuffer.allocate(sigSize(message))
    message.fromFingerprint.forEach { u ->
        buf.putLong(u.mostSignificantBits)
        buf.putLong(u.leastSignificantBits)
    }
    message.toFingerprint.forEach { u ->
        buf.putLong(u.mostSignificantBits)
        buf.putLong(u.leastSignificantBits)
    }
    buf.put(message.application.encodeToByteArray())
    buf.put(message.extension.encodeToByteArray())
    buf.put(message.mime.encodeToByteArray())
    buf.put(message.userFilename.encodeToByteArray())
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
    return LibsodiumInterface.sodium.crypto_sign_verify_detached(
            message.signature!!,
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
fun signEd25519(secretkey: ByteArray, message: Verifiable): ByteArray {
    if (secretkey.size != Sign.SECRETKEYBYTES) throw IllegalStateException("wrong keysize")
    val messagebytes = sumBytes(message)
    val sig = ByteArray(Sign.ED25519_BYTES)
    val p = PointerByReference(Pointer.NULL).pointer
    val res = LibsodiumInterface.sodium.crypto_sign_detached(
            sig,
            p,
            messagebytes.toByteArray(),
            messagebytes.size().toLong(),
            secretkey
    ) == 0
    if (!res) throw IllegalStateException("failed to sign")

    return sig
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

fun getGlobalHash(hashes: List<ByteArray>): ByteArray {
    val outhash = ByteArray(GenericHash.BYTES)
    val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
    LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, outhash.size)
    for (bytes in hashes) {
        LibsodiumInterface.sodium.crypto_generichash_update(state, bytes, bytes.size.toLong())
    }
    LibsodiumInterface.sodium.crypto_generichash_final(state, outhash, outhash.size)
    return outhash
}

fun getGlobalHashProto(hashes: List<ByteString>): ByteArray {
    val outhash = ByteArray(GenericHash.BYTES)
    val state = ByteArray(LibsodiumInterface.sodium.crypto_generichash_statebytes())
    LibsodiumInterface.sodium.crypto_generichash_init(state, null, 0, outhash.size)
    for (bytes in hashes) {
        LibsodiumInterface.sodium.crypto_generichash_update(state, bytes.toByteArray(), bytes.size().toLong())
    }
    LibsodiumInterface.sodium.crypto_generichash_final(state, outhash, outhash.size)
    return outhash
}

fun hashAsUUID(hash: ByteArray): UUID {
    return when {
        hash.size != GenericHash.BYTES -> {
            throw IllegalArgumentException("hash size wrong: ${hash.size}")
        }
        else -> {
            val buf = ByteBuffer.wrap(hash)
            //note: this only is safe because crypto_generichash_BYTES_MIN is 16
            UUID(buf.long, buf.long)
        }
    }
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


fun getDefaultFileName(hashes: List<ByteArray>): String {
    val buf = ByteBuffer.wrap(getGlobalHash(hashes))
    //note: this only is safe because crypto_generichash_BYTES_MIN is 16
    return UUID(buf.long, buf.long).toString()
}

fun getDefaultFileNameDb(hashes: List<Hashes>): String {
    val buf = ByteBuffer.wrap(getGlobalHashDb(hashes))
    //note: this only is safe because crypto_generichash_BYTES_MIN is 16
    return UUID(buf.long, buf.long).toString()
}

fun getDefaultFileNameProto(hashes: List<ByteString>): String {
    val buf = ByteBuffer.wrap(getGlobalHashProto(hashes))
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
    fun insertMessages(message: net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage): Completable
    fun insertMessage(stream: BlockDataStream): Completable
    fun insertMessages(messages: List<net.ballmerlabs.uscatterbrain.db.entities.ScatterMessage>): Completable
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
    fun getApiIdentityByFingerprint(identity: UUID): Single<ApiIdentity>
    fun addACLs(identityFingerprint: UUID, packagename: String, appsig: String): Completable
    fun deleteACLs(identityFingerprint: UUID, packageName: String, appsig: String): Completable
    fun getIdentityKey(identity: UUID): Single<ApiIdentity.KeyPair>
    fun messageCount(): Int
    fun deleteByPath(path: File): Int
    fun clear()
    fun deleteFile(path: File): Completable
    fun isOpen(path: File): Boolean
    fun close(path: File): Boolean
    fun open(path: File): Single<OpenFile>
    fun insertFile(stream: BlockDataStream): Single<Long>
    fun hashFile(path: File, blocksize: Int): Single<List<ByteArray>>
    fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket>
    fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket>
    fun getFilePath(packet: BlockHeaderPacket): File
    val cacheDir: File
    val userDir: File
    fun getFileSize(path: File): Long
    val allIdentities: List<Identity>
    fun getApiMessages(application: String): Single<ArrayList<ScatterMessage>>
    fun getApiMessagesSendDate(application: String, start: Date, end: Date): Single<ArrayList<ScatterMessage>>
    fun getApiMessagesReceiveDate(application: String, start: Date, end: Date): Single<ArrayList<ScatterMessage>>
    fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket>
    fun getApiMessages(id: Long): ScatterMessage
    fun insertAndHashFileFromApi(message: ScatterMessage, blocksize: Int,packageName: String, sign: UUID? = null): Completable
    val declareHashesPacket: Single<DeclareHashesPacket>
    fun getACLs(identity: UUID): Single<MutableList<ACL>>
    fun updatePackage(packageName: String): Completable
    fun getPackages(): Single<ArrayList<String>>
    fun deleteIdentities(vararg identity: UUID): Completable
    fun trimDatastore(cap: Date, max: Long, limit: Int? = null): Completable
    fun trimDatastore(packageName: String, max: Long): Completable
    fun trimDatastore(start: Date, end: Date, max: Long, limit: Int? = null): Completable
    fun deleteMessage(message: HashlessScatterMessage): Completable
    fun deleteMessage(message: File): Completable
    fun deleteMessage(message: ScatterMessage): Completable
    fun incrementShareCount(message: BlockHeaderPacket): Completable


    enum class WriteMode {
        APPEND, OVERWRITE
    }
}