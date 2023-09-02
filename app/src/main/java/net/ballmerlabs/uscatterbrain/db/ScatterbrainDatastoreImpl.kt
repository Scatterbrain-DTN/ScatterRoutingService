package net.ballmerlabs.uscatterbrain.db

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.os.ParcelUuid
import android.os.SharedMemory
import android.provider.DocumentsContract
import android.util.Pair
import android.webkit.MimeTypeMap
import com.github.davidmoten.rx2.Bytes
import com.google.protobuf.ByteString
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.BiFunction
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceBackend.Applications
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.entities.*
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.io.*
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.min

/***
 * Helper class used to construct ScatterMessages instances for the binder api while
 * accessing hidden fields
 *
 * @param from set the from identity fingerprint
 * @param id set the api uuid for this message
 */
class ApiMessageBuilder(from: UUID?, id: UUID) : ScatterMessage.Builder(
    fromFingerprint = from,
    id = ParcelUuid(id)
) {
    companion object {
        /**
         * creates a new Builder instance using an inline byte array without a file descriptor
         * @param data payload for this message
         * @param from sender fingerprints
         * @return builder class
         */
        fun newInstance(data: ByteArray, id: UUID, from: UUID?): ScatterMessage.Builder {
            val shared = SharedMemory.create("scatterMessage", data.size)
            val buf = shared.mapReadWrite()
            buf.put(data)
            return ApiMessageBuilder(from, id).setShm(shared)
        }

        /**
         * creates a new Builder instance using a file. Files are copied into the
         * Scatterbrain datastore when this messsage is inserted
         * @param file file for this message
         * @param from sender fingerprint
         * @return builder class
         */
        fun newInstance(file: File, id: UUID, from: UUID?): ScatterMessage.Builder {
            return ApiMessageBuilder(from, id).setFile(file)
        }

        /**
         * creates a new Builder instance using a file. Files are copied into the
         * Scatterbrain datastore when this messsage is inserted
         * @param descriptor file for this message
         * @param ext file extension
         * @param mime mime type
         * @param name filename for file
         * @param from sender fingerprint
         * @return builder class
         */
        fun newInstance(
            descriptor: FileDescriptor,
            ext: String,
            mime: String,
            name: String,
            id: UUID,
            from: UUID?
        ): ScatterMessage.Builder {
            return ApiMessageBuilder(from, id).setFile(
                ParcelFileDescriptor.dup(descriptor),
                ext,
                mime,
                name
            )
        }
    }
}

/**
 * Interface to the androidx room backed datastore
 * used for storing messages, identities, and associated files
 */
@Singleton
class ScatterbrainDatastoreImpl @Inject constructor(
    private val ctx: Context,
    private val mDatastore: Datastore,
    @param:Named(RoutingServiceComponent.NamedSchedulers.DATABASE) private val databaseScheduler: Scheduler,
    private val preferences: RouterPreferences
) : ScatterbrainDatastore {
    private val LOG by scatterLog()
    private val mOpenFiles: ConcurrentHashMap<File, OpenFile> = ConcurrentHashMap()
    private val userFilesDir: File = File(ctx.filesDir, USER_FILES_PATH)
    private val cacheFilesDir: File = File(ctx.filesDir, CACHE_FILES_PATH)
    private val userDirectoryObserver: FileObserver
    private val cachedPackages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val disposable = CompositeDisposable()

    override fun insertMessages(message: DbMessage): Completable {
        return mDatastore.scatterMessageDao().insertMessage(message)
            .subscribeOn(databaseScheduler)
    }

    override fun insertMessages(messages: List<DbMessage>): Completable {
        return Observable.fromIterable(messages)
            .subscribeOn(databaseScheduler)
            .flatMapCompletable { scatterMessage -> insertMessages(scatterMessage) }
    }

    override fun getPackages(): Single<ArrayList<String>> {
        return mDatastore.identityDao()
            .getAllPackageNames()
            .subscribeOn(databaseScheduler)
            .map { array ->
                val list = arrayListOf<String>()
                list.addAll(array)
                list
            }
    }

    override fun updatePackage(packageName: String): Completable {
        return Completable.defer {
            if (cachedPackages.contains(packageName)) {
                Completable.complete()
            } else {
                cachedPackages.add(packageName)
                mDatastore.identityDao().insertClientAppIgnore(
                    ClientApp(
                        null,
                        packageName,
                        null
                    )
                )
                    .subscribeOn(databaseScheduler)
            }
        }
    }

    private fun discardStream(stream: BlockDataStream): Completable {
        //TODO: we read and discard packets here because currently, but eventually
        // it would be a good idea to check the hash first and add support for aborting the transfer
        return stream.sequencePackets
            .flatMapCompletable { Completable.complete() }
    }

    override fun insertMessage(stream: BlockDataStream): Completable {
        return Completable.defer {
            stream.entity?.message?.receiveDate = Date().time
            if (stream.toDisk) {
                LOG.v("inserting message with disk")
                insertMessageWithDisk(stream)
            } else {
                LOG.v("inserting message without disk")
                insertMessagesWithoutDisk(stream)
            }
        }.andThen(
            trimDatastore(
                Date(Date().time - 120),
                getMax(), //TODO: parameterize this

            )
        )
    }

    private fun getMax(): Long {
        return ((preferences.getInt(ctx.getString(R.string.pref_sizecap), 4096)
            ?: (4096))).toLong() * 1024 * 1024
    }

    /**
     * Insert a blockdatastream to both database and disk
     */
    private fun insertMessageWithDisk(stream: BlockDataStream): Completable {
        return Completable.defer {
            val filePath = File(stream.entity!!.file.global.filePath)
            LOG.e("inserting message at filePath $filePath")
            mDatastore.scatterMessageDao().messageCountSingle(filePath.absolutePath)
                .subscribeOn(databaseScheduler)
                .flatMapCompletable { count ->
                    if (count > 0) {
                        discardStream(stream)
                    } else {
                        insertFile(stream)
                            .flatMapCompletable { size ->
                                stream.entity.message.fileSize = size
                                insertMessages(stream.entity)
                            }
                    }
                }
                .subscribeOn(databaseScheduler)
        }.doOnError { err ->
                LOG.e("error inserting messsage $err")
            }
    }

    /**
     * Insert a blockdatastream to database only
     * this implies a size limitation on body
     */
    private fun insertMessagesWithoutDisk(stream: BlockDataStream): Completable {
        return mDatastore.scatterMessageDao()
            .messageCountSingle(stream.headerPacket.autogenFilename)
            .subscribeOn(databaseScheduler)
            .doOnSubscribe { LOG.v("insertMessageWithoutDisk") }
            .flatMapCompletable { count ->
                if (count > 0) {
                    discardStream(stream)
                } else {
                    stream.sequencePackets
                        .flatMap { packet ->
                            LOG.v("insertMessageWithoutDisk ${packet.data.size}")

                            if (packet.verifyHash(stream.headerPacket)) {
                                Flowable.just(packet.data)
                            } else {
                                LOG.e("invalid hash")
                                Flowable.empty()
                            }
                        }
                        .reduce { obj, other -> obj + other }
                        .flatMapCompletable { bytes ->
                            if (bytes.size <= ScatterbrainApi.MAX_BODY_SIZE) {
                                stream.entity!!.message.body = bytes
                                stream.entity.message.fileSize = bytes.size.toLong()
                                LOG.v("inserting entity size ${bytes.size}")
                                insertMessages(stream.entity)
                                    .doOnError { err ->
                                        LOG.e("insertMessages error $err")
                                    }.onErrorComplete()
                            } else {
                                LOG.e("received message with invalid size ${bytes.size}, skipping")
                                Completable.complete()
                            }
                        }.subscribeOn(databaseScheduler)
                }
            }
            .doOnError { err-> LOG.e("failed to insertMessageWithoutDisk $err") }
            .doOnError { err ->
                LOG.e("error inserting messsage $err")
            }
    }

    override fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket> {
        return Bytes.from(ByteArrayInputStream(body), blocksize)
            .zipWith(seq) { bytes, seq ->
                BlockSequencePacket.newBuilder()
                    .setData(ByteString.copyFrom(bytes))
                    .setSequenceNumber(seq)
                    .build()
            }.concatWith(Flowable.just(BlockSequencePacket.newBuilder().setEnd(true).build()))
                .doOnComplete { LOG.v("readBody complete") }
    }

    override fun getTopRandomMessages(
        count: Int,
        delareHashes: DeclareHashesPacket
    ): Observable<BlockDataStream> {
        return Observable.defer {
            LOG.v("called getTopRandomMessages $count")
            mDatastore.scatterMessageDao().getTopRandomExclusingHash(count, delareHashes.hashes)
                .subscribeOn(databaseScheduler)
                .doOnSubscribe { LOG.v("subscribed to getTopRandoMessages") }
                .toFlowable()
                .doOnNext { message -> LOG.v("retrieved messages: " + message.size) }
                .flatMap { source -> Flowable.fromIterable(source) }

                .map { message ->
                    if (message.message.body == null) {
                        BlockDataStream(
                            message,
                            readFile(File(message.file.global.filePath), DEFAULT_BLOCKSIZE),
                            true
                        )
                    } else {
                        BlockDataStream(
                            message,
                            readBody(message.message.body!!, DEFAULT_BLOCKSIZE),
                            false
                        )
                    }
                }
                .toObservable()
                .doOnError { err ->
                    LOG.e("getTopRandomMessages error $err")
                }
                .concatWith(Single.just(BlockDataStream.endOfStream()))
                .onErrorReturnItem(BlockDataStream.endOfStream())
                .doOnComplete { LOG.v("getTopRandomMessages complete") }
        }
    }

    private val seq: Flowable<Int>
        get() = Flowable.generate(Callable { 0 }, BiFunction { state, emitter ->
            emitter.onNext(state)
            state + 1
        })

    override val allFiles: Observable<String>
        get() = mDatastore.scatterMessageDao().allFiles
            .toObservable()
            .flatMap { source -> Observable.fromIterable(source) }

    override fun getMessagesByIdentity(id: KeylessIdentity): Observable<DbMessage> {
        return mDatastore.scatterMessageDao().getByIdentity(id.fingerprint)
            .subscribeOn(databaseScheduler)
            .toObservable()
            .flatMap { source -> Observable.fromIterable(source) }
    }

    override fun getMessageByPath(path: String): Single<DbMessage> {
        return mDatastore.scatterMessageDao().getByFilePath(path)
            .subscribeOn(databaseScheduler)
            .toObservable()
            .flatMap { source -> Observable.fromIterable(source) }
            .firstOrError()
    }

    override fun trimDatastore(cap: Date, max: Long, limit: Int?): Completable {
        return trimDatastore(Date(0), cap, max, limit = limit)
    }

    private fun trimOnce(start: Date, end: Date): Single<Long> {
        return mDatastore.scatterMessageDao().getByReceiveDatePriority(start.time, end.time, 1)
            .subscribeOn(databaseScheduler)
            .flatMapObservable { list -> Observable.fromIterable(list) }
            .flatMapSingle { scatterMessage ->
                mDatastore.scatterMessageDao().delete(scatterMessage.message)
                    .andThen(deleteFile(File(scatterMessage.file.global.filePath)))
                    .toSingleDefault(scatterMessage.message.fileSize)
            }
            .firstOrError()
    }

    private fun trimOnce(packageName: String): Single<Long> {
        return mDatastore.scatterMessageDao().getByReceiveDatePriority(packageName, 1)
            .subscribeOn(databaseScheduler)
            .flatMapObservable { list -> Observable.fromIterable(list) }
            .flatMapSingle { scatterMessage ->
                mDatastore.scatterMessageDao().delete(scatterMessage.message)
                    .andThen(deleteFile(File(scatterMessage.file.global.filePath)))
                    .toSingleDefault(scatterMessage.message.fileSize)
            }
            .firstOrError()
    }

    override fun trimDatastore(packageName: String, max: Long): Completable {
        return mDatastore.scatterMessageDao().getTotalSize()
            .subscribeOn(databaseScheduler)
            .flatMapCompletable { size ->
                LOG.v("trimming $size")
                trimOnce(packageName)
                    .repeat()
                    .filter { s -> s > 0 }
                    .scan(size) { s, v ->
                        LOG.v("scan $s $v")
                        s + v
                    }
                    .takeUntil { v -> v <= max }
                    .ignoreElements()
            }
    }

    override fun trimDatastore(start: Date, end: Date, max: Long, limit: Int?): Completable {
        return mDatastore.scatterMessageDao().getTotalSize()
            .subscribeOn(databaseScheduler)
            .flatMapCompletable { size ->
                trimOnce(start, end)
                    .repeat()
                    .scan(size) { s, v -> s + v }
                    .takeUntil { v -> v <= max }
                    .ignoreElements()
            }
            .onErrorComplete()
    }

    private fun insertIdentity(identityObservable: Observable<net.ballmerlabs.uscatterbrain.db.entities.Identity>): Completable {
        return identityObservable
            .flatMapCompletable { singleid ->
                Single.fromCallable { mDatastore.identityDao().insertIdentity(singleid) }
                    .flatMapCompletable { identityId ->
                        Observable.fromCallable { if (singleid.clientACL != null) singleid.clientACL else ArrayList() }
                            .flatMap { source -> Observable.fromIterable(source) }
                            .map { acl ->
                                acl.identityFK = identityId
                                acl
                            }
                            .reduce(ArrayList<ClientApp>()) { list, acl ->
                                list.add(acl)
                                list
                            }
                            .flatMapCompletable { a ->
                                mDatastore.identityDao().insertClientAppsReplace(a)
                                    .subscribeOn(databaseScheduler)
                                    .ignoreElement()
                            }
                    }
                    .doOnError { e -> LOG.e("failed to insert identity: $e") }
                    .onErrorComplete()
            }
    }

    override fun deleteIdentities(vararg identity: UUID): Completable {
        return Observable.fromIterable(identity.asList())
            .map { f -> JustFingerprint(f) }
            .reduce(ArrayList<JustFingerprint>()) { list, f ->
                list.add(f)
                list
            }
            .flatMapCompletable { l ->
                mDatastore.identityDao().deleteIdentityByFingerprint(l)
                    .subscribeOn(databaseScheduler)
            }
    }

    override fun incrementShareCount(message: BlockHeaderPacket): Completable {
        return Single.just(message)
            .flatMapCompletable { m ->
                if (m.isEndOfStream) {
                    Completable.complete()
                } else {
                    mDatastore.scatterMessageDao().incrementShareCount(getGlobalHash(m.hashList))
                        .flatMapCompletable { v ->
                            if (v == 1)
                                Completable.complete()
                            else {
                                LOG.e("incremented share count for missing message $v")
                                Completable.complete()
                            }
                        }
                        .subscribeOn(databaseScheduler)
                }
            }
    }

    private fun insertIdentity(vararg ids: net.ballmerlabs.uscatterbrain.db.entities.Identity): Completable {
        return Single.just(ids)
            .flatMapCompletable { identities ->
                insertIdentity(Observable.fromArray(*identities))
                    .subscribeOn(databaseScheduler)

            }
    }

    private fun insertIdentity(ids: List<net.ballmerlabs.uscatterbrain.db.entities.Identity>): Completable {
        return Single.just(ids)
            .flatMapCompletable { identities ->
                insertIdentity(Observable.fromIterable(identities))
                    .subscribeOn(databaseScheduler)
            }
    }

    override fun addACLs(
        identityFingerprint: UUID,
        packagename: String,
        appsig: String
    ): Completable {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
            .subscribeOn(databaseScheduler)
            .flatMapCompletable { identity ->
                val app = ClientApp(
                    identity.identity.identityID!!,
                    packagename,
                    appsig
                )
                mDatastore.identityDao().insertClientAppIgnore(app)
                    .subscribeOn(databaseScheduler)
            }
    }

    override fun deleteACLs(
        identityFingerprint: UUID,
        packageName: String,
        appsig: String
    ): Completable {
        return mDatastore.identityDao().getIdentityByFingerprint(identityFingerprint)
            .subscribeOn(databaseScheduler)
            .flatMapCompletable { identity ->
                val app = ClientApp(
                    identity.identity.identityID!!,
                    packageName,
                    appsig
                )
                mDatastore.identityDao().deleteClientApps(JustPackageName(app.packageName))
                    .subscribeOn(databaseScheduler)
            }
    }

    override fun insertApiIdentity(identity: ApiIdentity): Completable {
        return Single.just(identity)
            .map { dbidentity ->
                val id = dbidentity.identity
                val kid = KeylessIdentity(
                    id.name,
                    id.publicKey,
                    id.sig,
                    id.fingerprint,
                    dbidentity.privateKey
                )
                Identity(
                    kid,
                    keys2keysBytes(id.extraKeys)
                )
            }.flatMapCompletable { ids ->
                this.insertIdentity(ids)
            }
    }

    override fun insertApiIdentities(identities: List<Identity>): Completable {
        return Observable.fromIterable(identities)
            .map { identity ->
                val kid = KeylessIdentity(
                    identity.name,
                    identity.publicKey,
                    identity.sig,
                    identity.fingerprint,
                    null
                )
                Identity(
                    kid,
                    keys2keysBytes(identity.extraKeys)
                )
            }.reduce(ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity>()) { list, id ->
                list.add(id)
                list
            }.flatMapCompletable { ids ->
                this.insertIdentity(ids)
            }
    }

    private fun keys2keysBytes(k: Map<String, ByteArray>): List<Keys> {
        val res: MutableList<Keys> = ArrayList()
        for ((key, value) in k) {
            val keys = Keys(
                key,
                value
            )
            res.add(keys)
        }
        return res
    }

    private fun keys2keys(k: Map<String, ByteString>): List<Keys> {
        val res: MutableList<Keys> = ArrayList()
        for ((key, value) in k) {
            val keys = Keys(
                key,
                value.toByteArray()
            )
            res.add(keys)
        }
        return res
    }

    private fun keys2map(keys: List<Keys>): Map<String, ByteArray> {
        val res = HashMap<String, ByteArray>()
        for (k in keys) {
            res[k.key] = k.value
        }
        return res
    }

    override fun insertIdentityPacket(identity: List<IdentityPacket>): Completable {
        return Observable.fromIterable(identity)
            .filter { i -> !i.isEnd }
            .doOnNext { id -> LOG.v("inserting identity: ${id.fingerprint}") }
            .flatMap { i ->
                if (i.isEnd || i.isEmpty() || i.pubkey == null || i.uuid == null) {
                    Observable.empty()
                } else {
                    val id = KeylessIdentity(
                        i.name,
                        i.pubkey,
                        i.getSig(),
                        i.uuid,
                        null

                    )
                    val finalIdentity = Identity(
                        id,
                        keys2keys(i.keymap)
                    )
                    if (!i.verifyed25519(i.pubkey)) {
                        LOG.e("identity " + i.name + " " + i.fingerprint + " failed sig check")
                        Observable.never()
                    } else {
                        Observable.just(finalIdentity)
                    }
                }
            }
            .reduce(ArrayList<net.ballmerlabs.uscatterbrain.db.entities.Identity>()) { list, id ->
                list.add(id)
                list
            }
            .flatMapCompletable { ids ->
                this.insertIdentity(ids)
            }
    }

    override fun getIdentity(ids: List<Long>): Observable<IdentityPacket> {
        return mDatastore.identityDao().getIdentitiesWithRelations(ids)
            .subscribeOn(databaseScheduler)
            .toObservable()
            .flatMap { idlist ->
                Observable.fromIterable(idlist)
                    .map { relation ->
                        val keylist: MutableMap<String, ByteString> = HashMap(relation.keys.size)
                        for (keys in relation.keys) {
                            keylist[keys.key] = ByteString.copyFrom(keys.value)
                        }
                        val identity: IdentityPacket = IdentityPacket.newBuilder()
                            .setName(relation.identity.givenName)
                            .setScatterbrainPubkey(ByteString.copyFrom(relation.identity.publicKey))
                            .setSig(relation.identity.signature)
                            .build()!!
                        identity.putAll(keylist)
                        identity
                    }
            }
    }

    override fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket> {
        return mDatastore.identityDao().getNumIdentities()
            .subscribeOn(databaseScheduler)
            .map { n -> min(count, n) }
            .flatMapPublisher { num ->
                mDatastore.identityDao().getTopRandom(num)
                    .subscribeOn(databaseScheduler)
                    .flatMapObservable { source -> Observable.fromIterable(source) }
                    .doOnComplete { LOG.v("datastore retrieved identities: $num") }
                    .doOnNext { LOG.v("retrieved single identity") }
                    .toFlowable(BackpressureStrategy.BUFFER)
                    .zipWith(seq) { identity, _ ->
                        IdentityPacket.newBuilder()
                            .setName(identity.identity.givenName)
                            .setScatterbrainPubkey(ByteString.copyFrom(identity.identity.publicKey))
                            .setSig(identity.identity.signature)
                            .build()!!
                    }
            }.concatWith(Single.just(IdentityPacket.newBuilder().setEnd().build()!!))

    }

    override val declareHashesPacket: Single<DeclareHashesPacket>
        get() = mDatastore.scatterMessageDao().getTopHashes(
            preferences.getInt(ctx.getString(R.string.pref_declarehashescap), 128)!!
        )
            .subscribeOn(databaseScheduler)
            .doOnSuccess { p -> LOG.v("retrieved declareHashesPacket from datastore: " + p.size) }
            .map { hash ->
                if (hash.isEmpty()) {
                    DeclareHashesPacket.newBuilder().optOut().build()
                } else {
                    DeclareHashesPacket.newBuilder().setHashesByte(hash).build()
                }
            }

    override fun getApiIdentityByFingerprint(identity: UUID): Single<ApiIdentity> {
        return mDatastore.identityDao().getIdentityByFingerprint(identity)
            .subscribeOn(databaseScheduler)
            .map { id ->
                ApiIdentity.newBuilder()
                    .setName(id.identity.givenName)
                    .addKeys(keys2map(id.keys))
                    .setSig(id.identity.signature)
                    .setHasPrivateKey(id.identity.privatekey != null)
                    .build()
            }
    }

    override fun getIdentityKey(identity: UUID): Single<ApiIdentity.KeyPair> {
        return mDatastore.identityDao().getIdentityByFingerprint(identity)
            .subscribeOn(databaseScheduler)
            .map { id ->
                checkNotNull(id.identity.privatekey) { "private key not found" }
                ApiIdentity.KeyPair(id.identity.privatekey!!, id.identity.publicKey)
            }
    }

    override fun getACLs(identity: UUID): Single<MutableList<ACL>> {
        return mDatastore.identityDao().getClientApps(identity)
            .subscribeOn(databaseScheduler)
            .flatMapObservable { source -> Observable.fromIterable(source) }
            .filter { app -> app.identityFK != null && app.packageSignature != null }
            .map { clientApp ->
                ACL(
                    clientApp.packageName,
                    clientApp.packageSignature!!
                )
            }
            .reduce(ArrayList()) { list, acl ->
                list.add(acl)
                list
            }
    }

    override val allIdentities: List<Identity>
        get() = mDatastore.identityDao().all
            .subscribeOn(databaseScheduler)
            .flatMapObservable { source -> Observable.fromIterable(source) }
            .map { identity ->
                ApiIdentity.newBuilder()
                    .setName(identity.identity.givenName)
                    .addKeys(keys2map(identity.keys))
                    .setSig(identity.identity.signature)
                    .setHasPrivateKey(identity.identity.privatekey != null)
                    .build()
            }.reduce(ArrayList<Identity>()) { list, id ->
                list.add(id.identity)
                list
            }.blockingGet()

    override fun getFileMetadataSync(path: File): Map<String, Serializable> {
        return getMessageByPath(path.absolutePath)
            .map { message ->
                val result = HashMap<String, Serializable>()
                result[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = message.file.global.filePath
                result[DocumentsContract.Document.COLUMN_MIME_TYPE] = message.message.mimeType
                result[DocumentsContract.Document.COLUMN_DISPLAY_NAME] =
                    message.message.userFilename
                result[DocumentsContract.Document.COLUMN_FLAGS] =
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE //TODO: is this enough?
                result[DocumentsContract.Document.COLUMN_SIZE] = getFileSize(path)
                result[DocumentsContract.Document.COLUMN_SUMMARY] = "shared via scatterbrain"
                result
            }
            .onErrorReturn { HashMap() }
            .blockingGet()
    }

    override fun insertAndHashLocalFile(path: File, blocksize: Int): Map<String, Serializable> {
        return hashFile(path, blocksize)
            .flatMapCompletable { hashes ->
                LOG.e("hashing local file, len:" + hashes.size)
                val globalhash = getGlobalHash(hashes)
                val message = HashlessScatterMessage(
                    body = null,
                    application = Applications.APPLICATION_FILESHARING,
                    sig = null,
                    sessionid = 0,
                    extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(path).toString()),
                    fileGlobalHash = globalhash,
                    userFilename = path.name,
                    mimeType = ScatterbrainApi.getMimeType(path),
                    sendDate = Date().time,
                    receiveDate = Date().time,
                    fileSize = path.length(),
                    packageName = ""
                )
                val hashedMessage = DbMessage(
                    message,
                    DiskFile(
                        messageHashes = HashlessScatterMessage.hash2hashs(hashes, globalhash),
                        global = GlobalHash(
                            globalhash = globalhash,
                            filePath = path.absolutePath //TODO: this needs to change to cacheDir
                        )
                        ),
                    arrayListOf(),
                    arrayListOf()
                )
                insertMessages(hashedMessage)
            }.toSingleDefault(getFileMetadataSync(path))
            .blockingGet()
    }

    private fun message2message(message: DbMessage): ScatterMessage {
        val body = message.message.body
        return if (body == null) {
            val f = File(message.file.global.filePath)
            val r: File = if (f.exists()) {
                f
            } else {
                throw java.lang.IllegalStateException("file ${f.absolutePath} doesn't exist")
            }
            ApiMessageBuilder.newInstance(
                r,
                message.file.global.uuid,
                message.fromFingerprint.firstOrNull()
            )
                .setApplication(message.message.application)
                .setTo(message.toFingerprint.firstOrNull())
                .build()
        } else {
            ApiMessageBuilder.newInstance(
                body,
                message.file.global.uuid,
                message.fromFingerprint.firstOrNull()
            )
                .setApplication(message.message.application)
                .setTo(message.toFingerprint.firstOrNull())
                .build()
        }
    }

    private fun getApiMessage(entities: Observable<DbMessage>): Single<ArrayList<ScatterMessage>> {
        return entities
            .map { message -> message2message(message) }
            .reduce(ArrayList<ScatterMessage>()) { list, m ->
                list.add(m)
                list
            }
    }

    private fun getApiMessage(entity: Single<DbMessage>): Single<ScatterMessage> {
        return entity.map { message -> message2message(message) }
    }

    /**
     * Takes an observable of ScatterMessage database entities and only
     * emits entities that pass an e25519 signature check
     *
     * NOTE: this only works if the corresponding identity is in the databaes.
     * TODO: ui element warning of identityless messages
     */
    private fun filterMessagesBySigCheck(messages: Observable<DbMessage>):
            Observable<DbMessage> {
        return messages.flatMapSingle { message ->
            val fingerprint = message.fromFingerprint.firstOrNull()
            if (fingerprint == null) {
                Single.just(message)
            } else {
                mDatastore.identityDao().getIdentityByFingerprintMaybe(fingerprint)
                    .zipWith(Maybe.just(message)) { id, m -> kotlin.Pair(id, m) }
                    .filter { pair -> verifyed25519(pair.first.identity.publicKey, pair.second) }
                    .map { pair -> pair.second }
                    .toSingle(message)
            }
        }
    }

    override fun getApiMessages(application: String): Single<ArrayList<ScatterMessage>> {
        return getApiMessage(mDatastore.scatterMessageDao()
            .getByApplication(application)
            .subscribeOn(databaseScheduler)
            .flatMapObservable { source ->
                filterMessagesBySigCheck(Observable.fromIterable(source))
            }
        )
    }

    override fun getApiMessagesReceiveDate(
        application: String,
        start: Date,
        end: Date
    ): Single<ArrayList<ScatterMessage>> {
        return getApiMessage(
            mDatastore.scatterMessageDao()
                .getByReceiveDate(application, start.time, end.time)
                .subscribeOn(databaseScheduler)
                .flatMapObservable { s -> filterMessagesBySigCheck(Observable.fromIterable(s)) }
        )
    }

    override fun getApiMessagesSendDate(
        application: String,
        start: Date,
        end: Date
    ): Single<ArrayList<ScatterMessage>> {
        return getApiMessage(
            mDatastore.scatterMessageDao()
                .getBySendDate(application, start.time, end.time)
                .subscribeOn(databaseScheduler)
                .flatMapObservable { s -> filterMessagesBySigCheck(Observable.fromIterable(s)) }
        )
    }

    override fun getApiMessages(id: Long): ScatterMessage {
        return getApiMessage(mDatastore.scatterMessageDao().getByID(id))
            .subscribeOn(databaseScheduler)
            .blockingGet()
    }

    override fun insertAndHashFileFromApi(
        message: ScatterMessage,
        blocksize: Int,
        packageName: String,
        sign: UUID?
    ): Completable {
        return Single.fromCallable { File.createTempFile("scatterbrain", "insert") }
            .flatMapCompletable { file ->
                if (message.isFile) {
                    copyFile(message.fileDescriptor!!.fileDescriptor, file)
                        .subscribeOn(databaseScheduler)
                        .andThen(hashFile(file, blocksize))
                        .flatMapCompletable { hashes ->
                            file.renameTo(DbMessage.getPath(
                                cacheDir,
                                message,
                                hashes
                            ))
                            val dbmessage = DbMessage.from(
                                    message,
                                    hashes,
                                    cacheDir,
                                    packageName = packageName
                                )
                                if (sign != null) {
                                    getIdentityKey(sign)
                                        .flatMapCompletable { keypair ->
                                            dbmessage.message.sig =
                                                signEd25519(keypair.secretkey, dbmessage)
                                            insertMessages(dbmessage)
                                        }
                                } else {
                                    insertMessages(dbmessage)
                                }
                        }.subscribeOn(databaseScheduler)
                } else {
                    val buf = message.shm!!.mapReadOnly()
                    val body = ByteArray(buf.remaining())
                    buf.get(body)
                    message.shm?.close()
                    hashData(body, blocksize)
                        .flatMapCompletable { hashes ->

                            val dbmessage = DbMessage.from(
                                message,
                                hashes,
                                cacheDir,
                                packageName = packageName,
                                bytes = body
                            )

                            if (sign != null) {
                                getIdentityKey(sign)
                                    .flatMapCompletable { keypair ->
                                        dbmessage.message.sig =
                                            signEd25519(keypair.secretkey, dbmessage)
                                        insertMessages(dbmessage)
                                    }
                            } else {
                                insertMessages(dbmessage)
                            }
                        }
                }
            }
            .andThen(trimDatastore(packageName, getMax()))
    }

    override fun deleteByPath(path: File): Int {
        return mDatastore.scatterMessageDao()
            .deleteByPath(path.absolutePath)
    }

    override fun messageCount(): Int {
        return mDatastore.scatterMessageDao().messageCount()
    }

    override fun clear() {
        mDatastore.clearAllTables()
    }

    override fun deleteFile(path: File): Completable {
        return Completable.fromAction {
            if (!path.exists()) {
                throw FileNotFoundException()
            }
            if (!close(path)) {
                throw IllegalStateException("failed to close file")
            }
            if (!path.delete()) {
                throw IllegalStateException("failed to delete file")
            }
        }
    }

    override fun isOpen(path: File): Boolean {
        return mOpenFiles.containsKey(path)
    }

    override fun close(path: File): Boolean {
        if (isOpen(path)) {
            val f = mOpenFiles[path]
            if (f != null) {
                try {
                    f.close()
                } catch (e: IOException) {
                    return false
                }
                mOpenFiles.remove(path)
            }
        }
        return true
    }

    override val cacheDir: File
        get() {
            if (!cacheFilesDir.exists()) {
                if (!cacheFilesDir.mkdirs()) {
                    throw java.lang.IllegalStateException("failed to create directory $cacheFilesDir")
                }
            }
            return cacheFilesDir
        }

    override val userDir: File
        get() {
            if (!userFilesDir.exists()) {
                if (!userFilesDir.mkdirs()) {
                    throw java.lang.IllegalStateException("failed to create directory $cacheFilesDir")
                }
            }
            return userFilesDir
        }

    override fun getFileSize(path: File): Long {
        return path.length()
    }

    override fun open(path: File): Single<OpenFile> {
        return Single.fromCallable {
            val old = mOpenFiles[path]
            if (old == null) {
                val f = OpenFile(path, false)
                mOpenFiles[path] = f
                f
            } else {
                old
            }
        }
    }

    override fun deleteMessage(message: GlobalHash): Completable {
        return mDatastore.scatterMessageDao().delete(message)
            .andThen(deleteFile(File(message.filePath)))
    }

    override fun deleteMessage(message: File): Completable {
        return mDatastore.scatterMessageDao().getByFilePath(message.absolutePath)
            .flatMapObservable { m -> Observable.fromIterable(m) }
            .flatMapCompletable { m -> deleteMessage(m.file.global) }

    }

    override fun deleteMessage(message: ScatterMessage): Completable {
        return Completable.defer {
            mDatastore.scatterMessageDao().getByUUID(message.id.uuid)
                .flatMapCompletable { m -> deleteMessage(m.file.global) }
        }
    }

    private fun insertSequence(
        packets: Flowable<BlockSequencePacket>,
        header: BlockHeaderPacket,
        path: File
    ): Completable {
        return Single.fromCallable { FileOutputStream(path) }
            .flatMapCompletable { fileOutputStream ->
                packets
                    .concatMapCompletable{ blockSequencePacket ->
                        if (!blockSequencePacket.verifyHash(header)) {
                            packets.ignoreElements()
                        } else {
                            Completable.fromAction { fileOutputStream.write(blockSequencePacket.data) }
                                .subscribeOn(databaseScheduler)
                        }
                    }
            }
    }

    override fun insertFile(stream: BlockDataStream): Single<Long> {
        return Single.defer {
            val file = File(stream.entity!!.file.global.filePath)
            Completable.fromAction {
                LOG.v("insertFile: $file")
                if (!file.createNewFile()) {
                    throw IllegalStateException("file $file already exists")
                }
            }.andThen(
                insertSequence(
                    stream.sequencePackets,
                    stream.headerPacket,
                    file
                )
            )
                .toSingleDefault(file.length())
        }
    }

    private fun copyFile(old: FileDescriptor, file: File): Completable {
        return Single.just(Pair(old, file))
            .flatMapCompletable { pair ->
                if (!pair.second.createNewFile()) {
                    LOG.w("copyFile overwriting existing file")
                }
                if (!pair.first.valid()) {
                    Completable.error(IllegalStateException("invalid file descriptor: " + pair.first))
                } else {
                    val `is` = FileInputStream(pair.first)
                    val os = FileOutputStream(pair.second)
                    Bytes.from(`is`)
                        .concatMapCompletable { bytes ->
                            Completable.fromAction { os.write(bytes) }
                                .subscribeOn(databaseScheduler)
                        }
                        .doFinally {
                            `is`.close()
                            os.close()
                        }
                }
            }
    }

    private fun hashData(data: ByteArray, blocksize: Int): Single<MutableList<ByteArray>> {
        return Bytes.from(ByteArrayInputStream(data), blocksize)
            .zipWith(seq) { b, seq ->
                BlockSequencePacket.newBuilder()
                    .setSequenceNumber(seq)
                    .setData(ByteString.copyFrom(b))
                    .build().calculateHash()
            }.reduce(ArrayList()) { list, b ->
                list.add(b)
                list
            }
    }

    override fun hashFile(path: File, blocksize: Int): Single<List<ByteArray>> {
        return Single.fromCallable<List<ByteArray>> {
            val r: MutableList<ByteArray> = ArrayList()
            if (!path.exists()) {
                throw IllegalStateException("file already exists")
            }
            val `is` = FileInputStream(path)
            val buf = ByteArray(blocksize)
            var read: Int
            var seqnum = 0
            while (`is`.read(buf).also { read = it } != -1) {
                val blockSequencePacket: BlockSequencePacket = BlockSequencePacket.newBuilder()
                    .setSequenceNumber(seqnum)
                    .setData(ByteString.copyFrom(buf, 0, read))
                    .build()
                r.add(blockSequencePacket.calculateHash())
                seqnum++
            }
            r
        }.subscribeOn(databaseScheduler)
    }

    override fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket> {
        LOG.v("called readFile $path")
        return if (!path.exists()) {
            Flowable.error(FileNotFoundException(path.toString()))
        } else Flowable.fromCallable { FileInputStream(path) }
            .doOnSubscribe { LOG.v("subscribed to readFile") }
            .flatMap {
                Bytes.from(path, blocksize)
                    .zipWith(seq) { bytes, seqnum ->
                        BlockSequencePacket.newBuilder()
                            .setSequenceNumber(seqnum)
                            .setData(ByteString.copyFrom(bytes))
                            .build()
                    }.subscribeOn(databaseScheduler)
            }.doOnComplete { LOG.v("readfile completed") }
            .concatWith(Flowable.just(BlockSequencePacket.newBuilder().setEnd(true).build()))
    }

    init {
        userDir //create user and cahce directories so we can monitor them
        cacheDir
        val d = getPackages()
            .subscribeOn(databaseScheduler)
            .timeout(5, TimeUnit.SECONDS, databaseScheduler)
            .subscribe(
                { packages -> cachedPackages.addAll(packages) },
                { err -> LOG.e("failed to initialize package cache: $err") }
            )

        disposable.add(d)

        userDirectoryObserver = object : FileObserver(userFilesDir.absolutePath) {
            override fun onEvent(i: Int, s: String?) {
                when (i) {
                    CLOSE_WRITE -> {
                        if (!s.isNullOrEmpty()) {
                            LOG.v("file closed in user directory; $s")
                            val f = File(userFilesDir, s)
                            if (!f.isDirectory) {
                                if (f.exists() && f.length() > 0) {
                                    insertAndHashLocalFile(f, DEFAULT_BLOCKSIZE)
                                } else if (f.length() == 0L) {
                                    LOG.e("file length was zero, not hashing")
                                } else {
                                    LOG.e("closed file does not exist, race condition??!")
                                }
                            }
                        }
                    }
                    OPEN -> {
                        if (s != null) {
                            LOG.v("file created in user directory: $s")
                        }
                    }
                    DELETE -> {
                        if (s != null) {
                            LOG.v("file deleted in user directory: $s")
                        }
                    }
                }
            }
        }
        userDirectoryObserver.startWatching()
    }
}