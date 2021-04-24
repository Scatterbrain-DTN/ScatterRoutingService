package net.ballmerlabs.uscatterbrain

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleService
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.*
import net.ballmerlabs.uscatterbrain.db.ApiScatterMessage
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore.ACL
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayList

/**
 * Main foreground service class for Scatterbrain.
 */
class ScatterRoutingService : LifecycleService() {
    private lateinit var mBackend: RoutingServiceBackend
    private val protocolDisposableSet = Collections.newSetFromMap(ConcurrentHashMap<Disposable, Boolean>())
    private val binder: ScatterbrainAPI.Stub = object : ScatterbrainAPI.Stub() {
        private fun checkPermission(permName: String): Boolean {
            val pm = applicationContext.packageManager
            return PackageManager.PERMISSION_GRANTED == pm.checkPermission(permName, callingPackageName)
        }

        // note: we have to get the package name from the Stub class because
        // the binder is called via a proxy that may run as a different process
        @get:Synchronized
        private val callingPackageName: String
            get() {
                var packageName: String? = null
                val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
                if (packages != null && packages.isNotEmpty()) {
                    packageName = packages[0]
                }
                return packageName ?: ""
            }

        @RequiresApi(api = Build.VERSION_CODES.P)
        @Synchronized
        @Throws(RemoteException::class)
        private fun verifyCallingSig(acl: ACL?) {
            try {
                val name = callingPackageName
                if (name != acl!!.packageName) {
                    throw RemoteException("invalid packagename, access denied")
                }
                val info = packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
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

        // fail if access permission is not granted
        @Throws(RemoteException::class)
        private fun checkAccessPermission() {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return
            }
            if (!checkPermission(getString(R.string.permission_access))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        // fail if admin permission is not granted
        @Throws(RemoteException::class)
        private fun checkAdminPermission() {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return
            }
            if (!checkPermission(getString(R.string.permission_admin))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        //fail if superuser permission is not granted
        @Throws(RemoteException::class)
        private fun checkSuperuserPermission() {
            if (!checkPermission(getString(R.string.permission_superuser))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        /**
         * Get scattermessage by database id.
         * @param id id unique to sqlite table
         * @return message matching id
         * @throws RemoteException if message not found
         */
        @Throws(RemoteException::class)
        override fun getById(id: Long): ScatterMessage {
            checkAccessPermission()
            return mBackend.datastore.getApiMessages(id)
        }


        /**
         * get all messages belonging to an application
         * @param application identifier
         * @return list of messages, may be zero
         */
        @Throws(RemoteException::class)
        override fun getByApplication(application: String): List<ScatterMessage> {
            checkAccessPermission()
            return mBackend.datastore.getApiMessages(application)
        }

        /**
         * get all identities stored in datastore. NOTE: this may be expensive to call
         * use with caution
         *
         * @return list of identities
         */
        @Throws(RemoteException::class)
        override fun getIdentities(): List<Identity> {
            checkAccessPermission()
            return mBackend.datastore.allIdentities
        }

        /**
         * get identity matching a specific fingerprint
         * @param fingerprint
         * @return identity
         */
        @Throws(RemoteException::class)
        override fun getIdentityByFingerprint(fingerprint: String): Identity {
            checkAccessPermission()
            return mBackend.datastore.getApiIdentityByFingerprint(fingerprint)
        }

        /**
         * enqueues a message onto the datastore.
         * @param message
         */
        @Throws(RemoteException::class)
        override fun sendMessage(message: ScatterMessage) {
            checkAccessPermission()
            mBackend.datastore.insertAndHashFileFromApi(ApiScatterMessage.fromApi(message), ScatterbrainDatastore.DEFAULT_BLOCKSIZE)
                    .doOnComplete { asyncRefreshPeers() }
                    .blockingAwait()
        }

        /**
         * enqueues a list of messages onto the datastore
         * @param messages list of messages
         */
        @Throws(RemoteException::class)
        override fun sendMessages(messages: List<ScatterMessage>) {
            checkAccessPermission()
            Observable.fromIterable(messages)
                    .flatMapCompletable { m: ScatterMessage ->
                        mBackend.datastore.insertAndHashFileFromApi(
                                ApiScatterMessage.fromApi(m),
                                ScatterbrainDatastore.DEFAULT_BLOCKSIZE)
                    }
                    .blockingAwait()
        }

        /**
         * sends a message and signs with specified identity fingerprint
         * @param message scattermessage to send
         * @param identity fingerprint of identity to sign with
         * @throws RemoteException if application is not authorized to use identity or if identity
         * does not exist
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(RemoteException::class)
        override fun sendAndSignMessage(message: ScatterMessage, identity: String) {
            val acls = mBackend.datastore.getACLs(identity).blockingGet()
            for (acl in acls!!) {
                verifyCallingSig(acl)
            }
            mBackend.datastore.getIdentityKey(identity)
                    .flatMapCompletable { id: ApiIdentity.KeyPair? ->
                        mBackend.datastore.insertAndHashFileFromApi(
                                ApiScatterMessage.fromApi(message, id),
                                ScatterbrainDatastore.DEFAULT_BLOCKSIZE)
                    }
                    .doOnComplete { asyncRefreshPeers() }
                    .blockingAwait()
        }

        /**
         * starts active discovery with one or more transport layers
         */
        @Throws(RemoteException::class)
        override fun startDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.start()
        }

        /**
         * stops active discovery with all transport layers
         */
        @Throws(RemoteException::class)
        override fun stopDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.stop()
        }

        /**
         * starts passive listening for connections on all transports
         */
        @Throws(RemoteException::class)
        override fun startPassive() {
            checkAdminPermission()
            mBackend.radioModule.startServer()
        }

        /**
         * stops passive listening for connections on all transports
         */
        @Throws(RemoteException::class)
        override fun stopPassive() {
            checkAdminPermission()
            mBackend.radioModule.stopServer()
        }

        /**
         * generates and stores a new identity
         * @param name name for this identity
         * @return identity object generated
         */
        @Throws(RemoteException::class)
        override fun generateIdentity(name: String): Identity {
            checkAdminPermission()
            val identity: ApiIdentity = ApiIdentity.newBuilder()
                    .setName(name)
                    .sign(ApiIdentity.newPrivateKey())
                    .build()
            val ret = mBackend.datastore.insertApiIdentity(identity)
                    .toSingleDefault(identity)
                    .doOnSuccess {
                        val stats = HandshakeResult(1,0, HandshakeResult.TransactionStatus.STATUS_SUCCESS)
                        val intent = Intent(getString(R.string.broadcast_message))
                        intent.putExtra(ScatterbrainApi.EXTRA_TRANSACTION_RESULT, stats)
                        sendBroadcast(intent, getString(R.string.permission_access))
                        asyncRefreshPeers()
                    }
                    .blockingGet()
            if (callingPackageName != applicationContext.packageName) {
                authorizeApp(identity.fingerprint, callingPackageName)
            }
            return ret
        }

        /**
         * removes an identity by fingerprint.
         * @param identity fingerprint of identity to remove
         * @return true if identity removed, false otherwise
         */
        @Throws(RemoteException::class)
        override fun removeIdentity(identity: String?): Boolean {
            return mBackend.datastore.deleteIdentities(identity!!)
                    .toSingleDefault(true)
                    .doOnError {
                        e -> Log.e(TAG, "failed to remove identity: $e")
                        e.printStackTrace()
                    }
                    .onErrorReturnItem(false)
                    .blockingGet()
        }

        /**
         * authorizes an app by package name to use a specific identity
         * @param identity fingerprint of identity to authorize
         * @param packagename package name of app
         * @throws RemoteException if package name not found or if permission denied
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(RemoteException::class)
        override fun authorizeApp(identity: String, packagename: String) {
            checkSuperuserPermission()
            try {
                val info = packageManager.getPackageInfo(packagename, PackageManager.GET_SIGNING_CERTIFICATES)
                for (signature in info.signingInfo.signingCertificateHistory) {
                    val sig = signature.toCharsString()
                    mBackend.datastore.addACLs(identity, packagename, sig).blockingAwait()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                throw RemoteException("invalid package name")
            }
        }

        /**
         * deauthorizes an app by package name to use a specific identity
         * @param identity fingerprint of identity to deauthorize
         * @param packagename package name of app
         * @throws RemoteException if package name not found or if permission denied
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(RemoteException::class)
        override fun deauthorizeApp(identity: String, packagename: String) {
            checkSuperuserPermission()
            try {
                val info = packageManager.getPackageInfo(packagename, PackageManager.GET_SIGNING_CERTIFICATES)
                for (signature in info.signingInfo.signingCertificateHistory) {
                    val sig = signature.toCharsString()
                    mBackend.datastore.deleteACLs(identity, packagename, sig).blockingAwait()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                throw RemoteException("invalid package name")
            }
        }

        /**
         * returns a list of packages by package name that are authorized to use an identity
         * @param identity identity by fingerprint
         * @return list of package names if any
         */
        override fun getAppPermissions(identity: String?): Array<String> {
            checkSuperuserPermission()
            return mBackend.datastore.getACLs(identity!!)
                    .flatMapObservable { acl -> Observable.fromIterable(acl) }
                    .map { acl -> acl.packageName }
                    .reduce(ArrayList<String>(), { list, acl ->
                        list.add(acl)
                        return@reduce list
                    })
                    .blockingGet()
                    .toTypedArray()
        }

        /**
         * returns true if active discovery is running
         * @return is discovering
         */
        @Throws(RemoteException::class)
        override fun isDiscovering(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isDiscovering
        }

        /**
         * returns true if passive listening
         * @return is passive
         */
        @Throws(RemoteException::class)
        override fun isPassive(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isPassive
        }
    }

    override fun onBind(i: Intent): IBinder {
        super.onBind(i)
        return binder
    }

    override fun onUnbind(i: Intent): Boolean {
        super.onUnbind(i)
        return true
    }

    private fun asyncRefreshPeers() {
        val disp = AtomicReference<Disposable>()
        val d = mBackend.radioModule.refreshPeers()
                .timeout(
                        mBackend.prefs.getLong(
                                getString(R.string.pref_transactiontimeout),
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
                { Log.v(TAG, "async refresh peers successful") },
                { err ->
                    Log.e(TAG, "error in async refresh peers: $err")
                    err.printStackTrace()
                }
        )
        protocolDisposableSet.add(d)
        disp.set(d)
    }

    /*
     * we initialize the service on start instead of on bind since multiple clients
     * may be bound at once
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            val c = DaggerRoutingServiceComponent.builder()
                    .applicationContext(this)
                    ?.build()!!
            component.accept(c)
            mBackend = c.scatterRoutingService()!!
            val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_FOREGROUND,
                    "fmef",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            val notificationIntent = Intent(this, ScatterRoutingService::class.java)
            val pendingIntent = PendingIntent.getService(this, 0, notificationIntent, 0)
            val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_FOREGROUND)
                    .setContentTitle("ScatterRoutingService")
                    .setContentText("discovering peers...\n(this uses location permission, but not actual geolocation)")
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentIntent(pendingIntent)
                    .setTicker("fmef am tire")
                    .build()
            startForeground(1, notification)
            Log.v(TAG, "called onbind")
            Log.v(TAG, "initialized datastore")
            mBackend.wifiDirect.registerReceiver()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.v(TAG, "exception")
        }
        return Service.START_NOT_STICKY
    }

    /* make sure to trigger disposal of any rxjava chains before shutting down */
    override fun onDestroy() {
        super.onDestroy()
        mBackend.radioModule.stopDiscover()
        mBackend.scheduler.stop()
        mBackend.wifiDirect.unregisterReceiver()
    }

    companion object {
        const val PROTO_VERSION = 5
        const val TAG = "ScatterRoutingService"
        private val component = BehaviorRelay.create<RoutingServiceComponent>()
        private const val NOTIFICATION_CHANNEL_FOREGROUND = "foreground"
        const val PERMISSION_DENIED_STR = "permission denied"
        fun getComponent(): Single<RoutingServiceComponent> {
            return component.firstOrError()
        }
    }
}