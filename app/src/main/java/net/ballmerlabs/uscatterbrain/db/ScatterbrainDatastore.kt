package net.ballmerlabs.uscatterbrain.db

import com.google.protobuf.ByteString
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.db.entities.*
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.regex.Pattern

const val DATABASE_NAME = "scatterdb"
const val DEFAULT_BLOCKSIZE = 1024 * 512
val FILE_SANITIZE: Pattern = Pattern.compile("^[a-zA-Z0-9_()-]*$")
const val USER_FILES_PATH = "userFiles"
const val CACHE_FILES_PATH = "systemFiles"

class ACL(val packageName: String, val appsig: String)

class OpenFile(path: File, append: Boolean) : Closeable {
    private val inputStream: FileInputStream
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

fun sanitizeFilename(name: String): String {
    return if (FILE_SANITIZE.matcher(name).matches()) {
        name
    } else {
        throw SecurityException("invalid filename")
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
    if (message.isFile) td = 1
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
    if (message.signature == null) return false
    if (pubkey.size != Sign.PUBLICKEYBYTES) return false
    val messagebytes = sumBytes(message)
    return LibsodiumInterface.sodium.crypto_sign_verify_detached(
            message.signature,
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

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and associated files
 */
interface ScatterbrainDatastore {
    /**
     * For internal use, synchronously inserts messages into the database
     * @param message list of room entities to insert
     * @return list of primary keys for rows inserted
     */
    fun insertMessages(message: net.ballmerlabs.uscatterbrain.db.entities.DbMessage): Completable

    /**
     * either insert a blockdatastream to disk or to database only depending on
     * toDisk flag
     * @param stream blockdatastream
     * @return completable
     */
    fun insertMessage(stream: BlockDataStream): Completable

    fun insertMessages(messages: List<net.ballmerlabs.uscatterbrain.db.entities.DbMessage>): Completable

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
    fun getMessagesByIdentity(id: KeylessIdentity): Observable<net.ballmerlabs.uscatterbrain.db.entities.DbMessage>

    /**
     * insert identity packets from network to database
     * @param identity list of identity packets to insert
     * @return completable
     */
    fun insertIdentityPacket(identity: List<IdentityPacket>): Completable

    /**
     * gets identities from database by id
     * @param ids
     * @return observable of IdentityPacket (completes even if none)
     */
    fun getIdentity(ids: List<Long>): Observable<IdentityPacket>

    /**
     * gets file metadata for use in DocumentsProvider
     * @param path file path
     * @return DocumentsProvider metadata
     */
    fun getFileMetadataSync(path: File): Map<String, Serializable>

    /**
     * inserts a local file into the database by calculating hashes based on blocksize
     * @param path filepath
     * @param blocksize size of packets
     * @return DocumentsProvider metadata
     */
    fun insertAndHashLocalFile(path: File, blocksize: Int): Map<String, Serializable>

    /**
     * reads a file at a given path and transforms it to a ScatterMessage database entity
     * @param path
     * @return single with scattermessage
     */
    fun getMessageByPath(path: String): Single<net.ballmerlabs.uscatterbrain.db.entities.DbMessage>

    /**
     * insert api identity to database
     * @param identity identity to insert
     * @return completable
     */
    fun insertApiIdentity(identity: ApiIdentity): Completable

    /**
     * insert multiple identities into database
     * @param identities list of identities
     * @return completable
     */
    fun insertApiIdentities(identities: List<Identity>): Completable

    /**
     * gets an identity by fingerprint in api form
     * @param identity
     * @return identity
     */
    fun getApiIdentityByFingerprint(identity: UUID): Single<ApiIdentity>

    /**
     * adds permission ACLs to the database
     * @param identityFingerprint identity
     * @param packagename package name to authorize
     * @param appsig signature of application. NOTE: make sure to get this right
     * @return completable
     */
    fun addACLs(identityFingerprint: UUID, packagename: String, appsig: String): Completable

    /**
     * removes permission ACLs from database
     * @param identityFingerprint identity
     * @param packageName package name to deauthorize
     * @param appsig signature of application
     */
    fun deleteACLs(identityFingerprint: UUID, packageName: String, appsig: String): Completable

    /**
     * get keypair for identity, including private key if possible
     * @param identity fingerprint
     * @return single of keypair
     */
    fun getIdentityKey(identity: UUID): Single<ApiIdentity.KeyPair>

    /**
     * gets total message count
     * @return count
     */
    fun messageCount(): Int

    /**
     * deletes a message by file path synchronously
     * @param path
     * @return id of message deleted
     */
    fun deleteByPath(path: File): Int

    /**
     * Clears the datastore, dropping all tables
     * NOTE: this should probably never be called
     */
    fun clear()

    /**
     * wrapper to safe delete a file
     * @param path
     * @return completable
     */
    fun deleteFile(path: File): Completable

    /**
     * checks if a file is cached open
     * @param path
     * @return true if file is open
     */
    fun isOpen(path: File): Boolean

    /**
     * close a file using cache
     * @param path
     * @return true of success, false if failure
     */
    fun close(path: File): Boolean
    fun open(path: File): Single<OpenFile>

    /**
     * insert blockdatastream with isFile to datastore
     */
    fun insertFile(stream: BlockDataStream): Single<Long>
    fun hashFile(path: File, blocksize: Int): Single<List<ByteArray>>
    fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket>

    /**
     * encode a binary blob as blocksequence packets
     * @param body data to encode
     * @param blocksize size of each packet
     * @return Flowable with BlockSequencePackets
     */
    fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket>

    val cacheDir: File

    val userDir: File

    /**
     * Wrapper function to get filesize
     * @param path file to get size from
     * @return size
     */
    fun getFileSize(path: File): Long

    /**
     * gets dump of all identities in database. Potentially expensive.
     */
    val allIdentities: List<Identity>

    /**
     * gets messages by application in api form
     * @param application
     * @return list of api messages
     */
    fun getApiMessages(application: String): Single<ArrayList<ScatterMessage>>

    /**
     * Filter messages by start and end date when message was sent
     * @param application
     * @param start
     * @param end
     *
     * @return list of api messages
     */
    fun getApiMessagesSendDate(application: String, start: Date, end: Date): Single<ArrayList<ScatterMessage>>

    /**
     * Filter messages by start and end date when message was received
     * @param application
     * @param start
     * @param end
     *
     * @return list of api messages
     */
    fun getApiMessagesReceiveDate(application: String, start: Date, end: Date): Single<ArrayList<ScatterMessage>>

    /**
     * gets random identities from database (in network form)
     * @param count
     * @return flowable of identitity packets
     */
    fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket>

    /**
     * gets messages in api form by database id
     * @param id database id
     * @return api message
     */
    fun getApiMessages(id: Long): ScatterMessage

    /**
     * inserts a file and calculates hashes from api data blob or ParcelFileDescriptor
     * @param message api message
     * @param blocksize blocksize
     * @return completable
     */
    fun insertAndHashFileFromApi(message: ScatterMessage, blocksize: Int,packageName: String, sign: UUID? = null): Completable

    /**
     * gets a single declarehashes packet based on the messages in the datastore
     */
    val declareHashesPacket: Single<DeclareHashesPacket>

    /**
     * gets all acls for identity
     * @param identity fingerprint
     * @return single of keypair list
     */
    fun getACLs(identity: UUID): Single<MutableList<ACL>>

    /**
     * registers a calling package with the cached database of package names
     * this is purely used to avoid api limitations in android 11 regarding
     * querying package names
     * @param packageName
     * @return Completable
     */
    fun updatePackage(packageName: String): Completable

    /**
     * gets a list of known packages that issued api calls in the past.
     * This is used to avoid api limitations in android 11
     * @return packages
     */
    fun getPackages(): Single<ArrayList<String>>

    /**
     * delete entities from database by fingerprint
     * @param identity
     * @return completable
     */
    fun deleteIdentities(vararg identity: UUID): Completable

    /**
     * Remove messages from the datastore in order of reverse importance
     * until size is less than or equal to max, capping the number of removed
     * messages at limit if limit is non-null
     * @param cap remove messages older than this date
     * @param max target datastore space usage
     * @param limit remove max this many messages
     */
    fun trimDatastore(cap: Date, max: Long, limit: Int? = null): Completable

    /**
     * Remove messages from the datastore in order of reverse importance
     * until size is less than or equal to max, limiting removed messages to messages
     * inserted via api by the given packagename
     * @param packageName limit removals to messages inserted by this package
     * @param max target datastore space usage
     */
    fun trimDatastore(packageName: String, max: Long): Completable
    /**
     * Remove messages from the datastore in order of reverse importance
     * until size is less than or equal to max, capping the number of removed
     * messages at limit if limit is non-null
     * @param start remove messages newer than this date
     * @param end remove messages older than this date
     * @param max target datastore space usage
     * @param limit remove max this many messages
     */
    fun trimDatastore(start: Date, end: Date, max: Long, limit: Int? = null): Completable

    /**
     * Deletes a message from the datastore
     * @param message message to delete
     * @return Completable
     */
    fun deleteMessage(message: GlobalHash): Completable

    /**
     * Deletes a message from the datastore
     * @param message message to delete
     * @return Completable
     */
    fun deleteMessage(message: File): Completable

    /**
     * Deletes a message from the datastore
     * @param message message to delete
     * @return Completable
     */
    fun deleteMessage(message: ScatterMessage): Completable

    /**
     * Increment the share count for a message counting the number of times
     * this message has been sent to a remote peer,
     *
     * This is used as a heuristic for ranking messages by importance
     *
     * @param message header packet for message to increment
     * @return completable that calls onError if the share count failed to increment
     */
    fun incrementShareCount(message: BlockHeaderPacket): Completable


    enum class WriteMode {
        APPEND, OVERWRITE
    }
}