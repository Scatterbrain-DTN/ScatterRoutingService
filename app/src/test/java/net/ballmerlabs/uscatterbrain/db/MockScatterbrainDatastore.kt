package net.ballmerlabs.uscatterbrain.db

import android.content.Context
import com.goterl.lazysodium.interfaces.Sign
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.internal.SbApp
import net.ballmerlabs.uscatterbrain.db.entities.*
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiIdentity
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopMessage
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import java.io.File
import java.io.Serializable
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import net.ballmerlabs.uscatterbrain.network.proto.*
@Singleton
class MockScatterbrainDatastore @Inject constructor(
    private val ctx: Context
): ScatterbrainDatastore {

    override fun deleteApp(sig: String): Completable {
        return Completable.complete()
    }

    override fun getDesktopIdentitiesByFingerprint(identity: UUID?): Single<List<DesktopApiIdentity>> {
        return Single.just(
            listOf( DesktopApiIdentity(
                fingerprint = UUID.randomUUID(),
                isOwned = false,
                name = "test",
                sig = ByteArray(Sign.BYTES),
                extraKeys = mapOf(),
                publicKey = ByteArray(Sign.PUBLICKEYBYTES)
            ))
        )
    }

    override fun getApps(): Observable<SbApp> {
        return Observable.just(SbApp(
            name = "",
            desktop = false,
            id = null
        ))
    }

    override fun updateStats(metrics: Metrics): Completable {
        return Completable.complete()
    }

    override fun insertMessageFromDesktop(
        message: List<DesktopMessage>,
        callingId: String,
        sign: UUID?,
    ): Completable {
        TODO("Not yet implemented")
    }

    override fun addACLs(packagename: String, packageSig: String?, desktop: Boolean): Completable {
        return Completable.complete()
    }

    override fun getStats(): Single<HandshakeResult> {
        return Single.just(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
    }

    override fun getStats(handshakeResult: HandshakeResult): Maybe<HandshakeResult> {
        return Maybe.empty()
    }

    override fun insertMessages(message: DbMessage): Completable {
        return Completable.complete()
    }

    override fun insertMessages(messages: List<DbMessage>): Completable {
        return Completable.complete()
    }

    override fun insertMessage(stream: WifiDirectRadioModule.BlockDataStream): Completable {
        return Completable.complete()
    }

    override fun getTopRandomMessages(count: Int, delareHashes: DeclareHashesPacket): Observable<WifiDirectRadioModule.BlockDataStream> {
        return Observable.empty()
    }

    override val allFiles: Observable<String>
        get() = Observable.empty()

    override fun getMessagesByIdentity(id: KeylessIdentity): Observable<DbMessage> {
        return Observable.empty()
    }

    override fun insertIdentityPacket(identity: List<IdentityPacket>): Completable {
        return Completable.complete()
    }

    override fun getIdentity(ids: List<Long>, owned: Boolean): Observable<IdentityPacket> {
        return Observable.empty()
    }

    override fun getFileMetadataSync(path: File): Map<String, Serializable> {
        return mapOf()
    }

    override fun insertAndHashLocalFile(path: File, blocksize: Int): Map<String, Serializable> {
        return mapOf()
    }

    override fun getMessageByPath(path: String): Single<DbMessage> {
        return Single.error(IllegalStateException("no messages here, cry noises"))
    }

    override fun insertApiIdentity(identity: ApiIdentity): Completable {
        return Completable.complete()
    }

    override fun insertApiIdentities(identities: List<net.ballmerlabs.scatterbrainsdk.Identity>): Completable {
        return Completable.complete()
    }

    override fun getApiIdentityByFingerprint(identity: UUID): Single<ApiIdentity> {
        return Single.error(IllegalStateException("no identities for u, cry noises"))
    }

    override fun addACLs(identityFingerprint: UUID, packagename: String, appsig: String, desktop: Boolean): Completable {
        return Completable.complete()
    }

    override fun deleteACLs(identityFingerprint: UUID, packageName: String, appsig: String): Completable {
        return Completable.complete()
    }

    override fun getIdentityKey(identity: UUID): Single<ApiIdentity.KeyPair> {
        return Single.error(IllegalStateException("no identities for u, cry noises"))
    }

    override fun messageCount(): Int {
        return 0
    }

    override fun deleteByPath(path: File): Int {
        return 0
    }

    override fun clear() {}

    override fun deleteFile(path: File): Completable {
        return Completable.complete()
    }

    override fun isOpen(path: File): Boolean {
        return false
    }

    override fun close(path: File): Boolean {
        return false
    }

    override fun open(path: File): Single<OpenFile> {
        return Single.error(IllegalStateException("unimplemented"))
    }

    override fun insertFile(stream: WifiDirectRadioModule.BlockDataStream): Single<Long> {
        return Single.just(0)
    }

    override fun hashFile(path: File, blocksize: Int): Single<List<ByteArray>> {
        return Single.just(listOf())
    }

    override fun readFile(path: File, blocksize: Int): Flowable<BlockSequencePacket> {
        return Flowable.error(IllegalStateException("no file for u, cry noises"))
    }

    override fun readBody(body: ByteArray, blocksize: Int): Flowable<BlockSequencePacket> {
        return Flowable.error(IllegalStateException("no body for u, cry noises"))
    }

    override val cacheDir: File
        get() = File("/dev/null")
    override val userDir: File
        get() = File("/dev/null")

    override fun getFileSize(path: File): Long {
        return path.length()
    }

    override val allIdentities: List<net.ballmerlabs.scatterbrainsdk.Identity>
        get() = listOf()

    override fun getApiMessages(application: String, limit: Int): Single<ArrayList<net.ballmerlabs.scatterbrainsdk.ScatterMessage>> {
        return Single.error(IllegalStateException("no apiMessages for u, cry noises"))
    }

    override fun getApiMessages(id: Long): net.ballmerlabs.scatterbrainsdk.ScatterMessage {
        return net.ballmerlabs.scatterbrainsdk.ScatterMessage.Builder.newInstance(ctx,byteArrayOf()).build()
    }

    override fun getApiMessagesSendDate(application: String, start: Date, end: Date, limit: Int): Single<ArrayList<net.ballmerlabs.scatterbrainsdk.ScatterMessage>> {
        return Single.error(IllegalStateException("no apiMessages for u, cry noises"))
    }

    override fun getApiMessagesReceiveDate(application: String, start: Date, end: Date, limit: Int): Single<ArrayList<net.ballmerlabs.scatterbrainsdk.ScatterMessage>> {
        return Single.error(IllegalStateException("no apiMessages for u, cry noises"))
    }

    override fun getTopRandomIdentities(count: Int): Flowable<IdentityPacket> {
        return Flowable.empty()
    }

    override fun insertAndHashFileFromApi(message: net.ballmerlabs.scatterbrainsdk.ScatterMessage, blocksize: Int, packageName: String, sign: UUID?): Completable {
        return Completable.complete()
    }

    override val declareHashesPacket: Single<DeclareHashesPacket>
        get() = Single.just(DeclareHashesPacket.newBuilder().setHashesByte(listOf()).build())

    override fun getACLs(identity: UUID): Single<MutableList<ACL>> {
        return Single.just(mutableListOf())
    }

    override fun updatePackage(packageName: String): Completable {
        return Completable.complete()
    }

    override fun getPackages(): Single<ArrayList<String>> {
        TODO("Not yet implemented")
    }

    override fun deleteIdentities(vararg identity: UUID): Completable {
        TODO("Not yet implemented")
    }

    override fun trimDatastore(cap: Date, max: Long, limit: Int?): Completable {
        TODO("Not yet implemented")
    }

    override fun trimDatastore(packageName: String, max: Long): Completable {
        TODO("Not yet implemented")
    }

    override fun trimDatastore(start: Date, end: Date, max: Long, limit: Int?): Completable {
        TODO("Not yet implemented")
    }

    override fun deleteMessage(message: GlobalHash): Completable {
        TODO("Not yet implemented")
    }

    override fun deleteMessage(message: File): Completable {
        TODO("Not yet implemented")
    }

    override fun deleteMessage(message: net.ballmerlabs.scatterbrainsdk.ScatterMessage): Completable {
        TODO("Not yet implemented")
    }

    override fun incrementShareCount(message: BlockHeaderPacket): Completable {
        TODO("Not yet implemented")
    }
}