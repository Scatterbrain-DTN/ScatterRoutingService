package net.ballmerlabs.uscatterbrain

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.wifi.p2p.WifiP2pManager
import android.os.RemoteException
import com.goterl.lazysodium.interfaces.Sign
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.db.ACL
import net.ballmerlabs.uscatterbrain.db.DEFAULT_BLOCKSIZE
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeStateImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parent class for the entire scatterbrain backend. This is what is
 * created by dagger2 builder in ScatterRoutingService
 */
@Singleton
class RoutingServiceBackendImpl @Inject constructor(
    override val datastore: ScatterbrainDatastore,
    override val scheduler: ScatterbrainScheduler,
    override val prefs: RouterPreferences,
    override val leState: LeState,
    val transactionBuilder: ScatterbrainTransactionFactory,
    val wifiDirectBroadcastReceiver: WifiDirectBroadcastReceiver,
    val context: Context
) : RoutingServiceBackend {
    private val LOG by scatterLog()
    private val protocolDisposableSet =
        Collections.newSetFromMap(ConcurrentHashMap<Disposable, Boolean>())

    init {
        LOG.e("initializing backend")
        RxJavaPlugins.setErrorHandler { e: Throwable ->
            LOG.e("received an unhandled exception: $e")
            e.printStackTrace()
        }
        // RxBleLOG.setLogLevel(RxBleLOG.VERBOSE)
    }

    /**
     * verify the signature of the calling package to determine if it can
     * access the current api call.
     *
     * TODO: on devices lower than api28 we do NOT support multiple signatures
     * This is a workaround for
     * https://android.googlesource.com/platform/tools/base/+/master/lint/libs/lint-checks/src/main/java/com/android/tools/lint/checks/GetSignaturesDetector.java#62
     *
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Synchronized
    @Throws(RemoteException::class)
    private fun verifyCallingSig(acl: ACL, callingPackageName: String): Completable {
        return Completable.fromAction {
            try {
                if (callingPackageName != acl.packageName) {
                    throw RemoteException("invalid packagename, access denied")
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val info = context.packageManager.getPackageInfo(
                        callingPackageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    var failed = true
                    for (signature in info.signingInfo.signingCertificateHistory) {
                        val sigtoverify = signature.toCharsString()
                        if (sigtoverify == acl.appsig) {
                            failed = false
                        }
                    }
                    if (failed) {
                        throw RemoteException("invalid signature, access denied")
                    }
                } else {
                    val info = context.packageManager.getPackageInfo(
                        callingPackageName,
                        PackageManager.GET_SIGNATURES
                    )
                    for (sig in info.signatures) {
                        if (sig.toCharsString() != acl.appsig) {
                            throw RemoteException("invalid signature, access denied")
                        }
                    }

                }

            } catch (e: PackageManager.NameNotFoundException) {
                throw RemoteException("invalid package name")
            }
        }
    }

    override fun registerReceiver() {
        try {
            LOG.v("registering broadcast receiver")
            val intentFilter = IntentFilter()
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)

            // Indicates a change in the list of available peers.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)

            // Indicates the state of Wi-Fi P2P connectivity has changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)

            // Indicates this device's details have changed.
            intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            context.registerReceiver(wifiDirectBroadcastReceiver.asReceiver(), intentFilter)
        } catch (exc: Exception) {
            LOG.e("failed to register receiver, ignoring $exc")
        }
    }

    override fun verifyData(data: ByteArray, sig: ByteArray, identity: UUID): Single<Boolean> {
        return datastore.getIdentityKey(identity)
            .map { keypair ->
                val status = LibsodiumInterface.sodium.crypto_sign_verify_detached(
                    sig,
                    data,
                    data.size.toLong(),
                    keypair.publickey
                )
                status == 0
            }
    }

    override fun signDataDetached(
        data: ByteArray,
        identity: UUID,
        callingPackageName: String
    ): Single<ByteArray> {
        return datastore.getACLs(identity)
            .flatMapObservable { Observable.fromIterable(it) }
            .flatMapCompletable { acl -> verifyCallingSig(acl, callingPackageName) }
            .andThen(datastore.getIdentityKey(identity)
                .map { keypair ->
                    val res = ByteArray(Sign.ED25519_BYTES)
                    val p = PointerByReference(Pointer.NULL).pointer
                    val status = LibsodiumInterface.sodium.crypto_sign_detached(
                        res,
                        p,
                        data,
                        data.size.toLong(),
                        keypair.secretkey
                    )
                    if (status != 0) {
                        throw IllegalStateException("failed to sign: $status")
                    }
                    res
                })

    }

    override fun sendAndSignMessage(
        message: ScatterMessage,
        identity: UUID,
        callingPackageName: String
    ): Completable {
        return datastore.getACLs(identity)
            .flatMapObservable { Observable.fromIterable(it) }
            .flatMapCompletable { acl -> verifyCallingSig(acl, callingPackageName) }
            .andThen(
                datastore.insertAndHashFileFromApi(
                    message,
                    DEFAULT_BLOCKSIZE,
                    callingPackageName,
                    identity
                )
                    .doOnComplete { asyncRefreshPeers() }
            )

    }

    override fun sendAndSignMessages(
        messages: List<ScatterMessage>,
        identity: UUID,
        callingPackageName: String
    ): Completable {
        return datastore.getACLs(identity)
            .flatMapObservable { Observable.fromIterable(it) }
            .flatMapCompletable { acl -> verifyCallingSig(acl, callingPackageName) }
            .andThen(
                Observable.fromIterable(messages)
                    .flatMapCompletable { message ->
                        datastore.insertAndHashFileFromApi(
                            message,
                            DEFAULT_BLOCKSIZE,
                            callingPackageName,
                            identity
                        )
                    }
                    .doOnComplete { asyncRefreshPeers() }
            )
            .doOnError { err ->
                LOG.e("failed sendAndSignMessages: $err")
                err.printStackTrace()
            }
    }

    override fun generateIdentity(name: String, callingPackageName: String): Single<Identity> {
        return Single.defer {
            val apiidentity = ApiIdentity.newBuilder()
                .setName(name)
                .sign(ApiIdentity.newPrivateKey())
                .build()
            val identity = apiidentity.identity
            datastore.insertApiIdentity(apiidentity)
                .andThen(authorizeApp(identity.fingerprint, callingPackageName))
                .toSingleDefault(identity)
                .doOnSuccess {
                    val stats =
                        HandshakeResult(1, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS)
                    val intent = Intent(ScatterbrainApi.BROADCAST_EVENT)
                    intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, stats)
                    context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
                    asyncRefreshPeers()
                }
        }
    }

    override fun getIdentity(fingerprint: UUID): Single<Identity> {
        return datastore.getApiIdentityByFingerprint(fingerprint)
            .map { id -> id.identity }
    }

    override fun removeIdentity(name: UUID, callingPackageName: String): Completable {
        return datastore.deleteIdentities(name)
            .doOnError { e ->
                LOG.e("failed to remove identity: $e")
                e.printStackTrace()
            }
    }

    override fun sendMessage(message: ScatterMessage, callingPackageName: String): Completable {
        return datastore.insertAndHashFileFromApi(message, DEFAULT_BLOCKSIZE, callingPackageName)
            .doOnComplete { asyncRefreshPeers() }
    }

    override fun sendMessages(
        messages: List<ScatterMessage>,
        callingPackageName: String
    ): Completable {
        return Observable.fromIterable(messages)
            .flatMapCompletable { m ->
                datastore.insertAndHashFileFromApi(
                    m,
                    DEFAULT_BLOCKSIZE,
                    callingPackageName
                )
            }
    }

    @SuppressLint("PackageManagerGetSignatures")
    private fun getSigs(name: String): Observable<Signature> {
        return Observable.defer {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    name,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                Observable.fromIterable(info.signingInfo.signingCertificateHistory.asIterable())
            } else {
                val info =
                    context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
                Observable.fromIterable(info.signatures.toMutableList())
            }
        }
    }

    override fun deauthorizeApp(fingerprint: UUID, packageName: String): Completable {
        return Observable.just(packageName).flatMap { name -> getSigs(name) }
            .flatMapCompletable { signature ->
                val sig = signature.toCharsString()
                datastore.deleteACLs(fingerprint, packageName, sig)
            }
    }

    override fun authorizeApp(fingerprint: UUID, packageName: String): Completable {
        return Observable.just(packageName).flatMap { name -> getSigs(name) }
            .flatMapCompletable { signature ->
                val sig = signature.toCharsString()
                datastore.addACLs(fingerprint, packageName, sig)
            }

    }

    override fun refreshPeers(): Completable {
        return leState.refreshPeers()
            .timeout(
                prefs.getLong(
                    context.getString(R.string.pref_transactiontimeout),
                    RoutingServiceBackend.DEFAULT_TRANSACTIONTIMEOUT
                )!!,
                TimeUnit.SECONDS
            )
    }

    private fun asyncRefreshPeers() {
        LOG.v("asyncRefreshPeers")
        val disp = AtomicReference<Disposable>()
        val d = refreshPeers()
            .doFinally {
                val d = disp.get()
                if (d != null) {
                    protocolDisposableSet.remove(d)
                }
            }
            .timeout(
                prefs.getLong(
                    context.getString(R.string.pref_transactiontimeout),
                    RoutingServiceBackend.DEFAULT_TRANSACTIONTIMEOUT
                )!!,
                TimeUnit.SECONDS
            )
            .doFinally {
                val d = disp.get()
                if (d != null) {
                    protocolDisposableSet.remove(d)
                }
            }
            .subscribe(
                { LOG.v("async refresh peers successful") },
                { err ->
                    LOG.e("error in async refresh peers: $err")
                    err.printStackTrace()
                }
            )
        protocolDisposableSet.add(d)
        disp.set(d)
    }
}