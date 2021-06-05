package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.polidea.rxandroidble2.internal.RxBleLog
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.scatterbrainsdk.internal.HandshakeResult
import net.ballmerlabs.uscatterbrain.db.ACL
import net.ballmerlabs.uscatterbrain.db.DEFAULT_BLOCKSIZE
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Parent class for the entire scatterbrain backend. This is what is
 * created by dagger2 builder in ScatterRoutingService
 */
class RoutingServiceBackendImpl @Inject constructor(
        override val datastore: ScatterbrainDatastore,
        override val radioModule: BluetoothLEModule,
        override val scheduler: ScatterbrainScheduler,
        override val wifiDirect: WifiDirectRadioModule,
        override val prefs: RouterPreferences,
        val context: Context
) : RoutingServiceBackend {
    private val protocolDisposableSet = Collections.newSetFromMap(ConcurrentHashMap<Disposable, Boolean>())

    companion object {
        const val TAG = "RoutingServiceBackend"
    }

    init {
        RxJavaPlugins.setErrorHandler { e: Throwable ->
            Log.e(TAG, "received an unhandled exception: $e")
            e.printStackTrace()
        }
        RxBleLog.setLogLevel(RxBleLog.DEBUG) //TODO: disable this in production
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @Synchronized
    @Throws(RemoteException::class)
    private fun verifyCallingSig(acl: ACL, callingPackageName: String) :Completable {
        return Completable.fromAction {
            try {
                val name = callingPackageName
                if (name != acl.packageName) {
                    throw RemoteException("invalid packagename, access denied")
                }
                val info = context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
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
            } catch (e: PackageManager.NameNotFoundException) {
                throw RemoteException("invalid package name")
            }
        }
    }

    override fun signDataDetached(data: ByteArray, identity: String, callingPackageName: String): Single<ByteArray> {
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

    override fun sendAndSignMessage(message: ScatterMessage, identity: String, callingPackageName: String): Completable {
        return datastore.getACLs(identity)
                .flatMapObservable { Observable.fromIterable(it) }
                .flatMapCompletable { acl -> verifyCallingSig(acl, callingPackageName) }
                .andThen(
                        datastore.insertAndHashFileFromApi(
                                message,
                                DEFAULT_BLOCKSIZE,
                                identity
                        )
                                .doOnComplete { asyncRefreshPeers() }
                )

    }

    override fun sendAndSignMessages(messages: List<ScatterMessage>, identity: String, callingPackageName: String): Completable {
        return datastore.getACLs(identity)
                .flatMapObservable { Observable.fromIterable(it) }
                .flatMapCompletable { acl -> verifyCallingSig(acl, callingPackageName) }
                .andThen(
                        Observable.fromIterable(messages)
                                .flatMapCompletable { message ->
                                    datastore.insertAndHashFileFromApi(
                                            message,
                                            DEFAULT_BLOCKSIZE,
                                            identity
                                    )
                                }
                                .doOnComplete { asyncRefreshPeers() }
                )
    }

    override fun generateIdentity(name: String, callingPackageName: String): Single<Identity> {
        return Single.defer {
            val identity: ApiIdentity = ApiIdentity.newBuilder()
                    .setName(name)
                    .sign(ApiIdentity.newPrivateKey())
                    .build()
            datastore.insertApiIdentity(identity)
                    .andThen(authorizeApp(identity.fingerprint, callingPackageName))
                    .toSingleDefault(identity)
                    .doOnSuccess {
                        val stats = HandshakeResult(1,0, HandshakeResult.TransactionStatus.STATUS_SUCCESS)
                        val intent = Intent(context.getString(R.string.broadcast_message))
                        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, stats)
                        context.sendBroadcast(intent, ScatterbrainApi.PERMISSION_ACCESS)
                        asyncRefreshPeers()
                    }
        }
    }

    override fun removeIdentity(name: String, callingPackageName: String): Completable {
        return datastore.deleteIdentities(name)
                .doOnError {
                    e -> Log.e(ScatterRoutingService.TAG, "failed to remove identity: $e")
                    e.printStackTrace()
                }
    }

    override fun sendMessage(message: ScatterMessage): Completable {
        return datastore.insertAndHashFileFromApi(message, DEFAULT_BLOCKSIZE)
                .doOnComplete { asyncRefreshPeers() }
    }

    override fun sendMessages(messages: List<ScatterMessage>): Completable {
        return Observable.fromIterable(messages)
                .flatMapCompletable { m ->
                    datastore.insertAndHashFileFromApi(
                            m,
                            DEFAULT_BLOCKSIZE
                    )
                }
    }

    override fun deauthorizeApp(fingerprint: String, packageName: String): Completable {
        return Completable.defer {
            if (packageName == context.packageName) {
                Log.e(TAG, "attempted to deauthorize ourselves")
                Completable.complete()
            } else {
                Observable.just(packageName).flatMap { name ->
                    val info = context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                    Observable.fromIterable(info.signingInfo.signingCertificateHistory.asIterable())
                }
                        .flatMapCompletable { signature ->
                            val sig = signature.toCharsString()
                            datastore.deleteACLs(fingerprint, packageName, sig)
                        }
            }

        }
    }

    override fun authorizeApp(fingerprint: String, packageName: String): Completable {
        return Completable.defer {
            if (packageName == context.packageName) {
                Log.e(TAG, "attempted to auauthorize ourselves")
                Completable.complete()
            } else {
                Observable.just(packageName).flatMap { name ->
                    val info = context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                    Observable.fromIterable(info.signingInfo.signingCertificateHistory.asIterable())
                }
                        .flatMapCompletable { signature ->
                            val sig = signature.toCharsString()
                            datastore.addACLs(fingerprint, packageName, sig)
                        }
            }

        }

    }

    private fun asyncRefreshPeers() {
        val disp = AtomicReference<Disposable>()
        val d = radioModule.refreshPeers()
                .timeout(
                        prefs.getLong(
                                context.getString(R.string.pref_transactiontimeout),
                                RoutingServiceBackend.DEFAULT_TRANSACTIONTIMEOUT
                        ),
                        TimeUnit.SECONDS
                )
                .doFinally {
                    val d = disp.get()
                    if (d != null) {
                        protocolDisposableSet.remove(d)
                    }
                }
                .subscribe(
                        { Log.v(ScatterRoutingService.TAG, "async refresh peers successful") },
                        { err ->
                            Log.e(ScatterRoutingService.TAG, "error in async refresh peers: $err")
                            err.printStackTrace()
                        }
                )
        protocolDisposableSet.add(d)
        disp.set(d)
    }
}