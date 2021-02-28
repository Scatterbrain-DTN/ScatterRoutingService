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
import net.ballmerlabs.scatterbrainsdk.Identity
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainAPI
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.db.ApiScatterMessage
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore.ACL
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.Throws

class ScatterRoutingService : LifecycleService() {
    val TAG = "ScatterRoutingService"
    private val mBinder: IBinder = ScatterBinder()
    private val mBackend: RoutingServiceBackend
        get() {
            val c = DaggerRoutingServiceComponent.builder()
                    .applicationContext(this)
                    ?.build()!!
            component.accept(c)
            return c.scatterRoutingService()!!
        }
    private val bound = AtomicReference(false)
    private val binder: ScatterbrainAPI.Stub = object : ScatterbrainAPI.Stub() {
        private fun checkPermission(permName: String): Boolean {
            val pm = applicationContext.packageManager
            return PackageManager.PERMISSION_GRANTED == pm.checkPermission(permName, callingPackageName)
        }

        // permission check
        @get:Synchronized
        private val callingPackageName: String
            private get() {
                // permission check
                var packageName: String? = null
                val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
                if (packages != null && packages.size > 0) {
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

        @Throws(RemoteException::class)
        private fun checkAccessPermission() {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return
            }
            if (!checkPermission(getString(R.string.permission_access))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        @Throws(RemoteException::class)
        private fun checkAdminPermission() {
            if (checkPermission(getString(R.string.permission_superuser))) {
                return
            }
            if (!checkPermission(getString(R.string.permission_admin))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        @Throws(RemoteException::class)
        private fun checkSuperuserPermission() {
            if (!checkPermission(getString(R.string.permission_superuser))) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        @Throws(RemoteException::class)
        override fun getById(id: Long): ScatterMessage {
            checkAccessPermission()
            return mBackend.datastore.getApiMessages(id)
        }

        @Throws(RemoteException::class)
        override fun getByApplication(application: String): List<ScatterMessage> {
            checkAccessPermission()
            return mBackend.datastore.getApiMessages(application)
        }

        @Throws(RemoteException::class)
        override fun getIdentities(): List<Identity> {
            checkAccessPermission()
            return mBackend.datastore.allIdentities
        }

        @Throws(RemoteException::class)
        override fun getIdentityByFingerprint(fingerprint: String): Identity {
            checkAccessPermission()
            return mBackend.datastore.getApiIdentityByFingerprint(fingerprint)
        }

        @Throws(RemoteException::class)
        override fun sendMessage(message: ScatterMessage) {
            checkAccessPermission()
            mBackend.datastore.insertAndHashFileFromApi(ApiScatterMessage.Companion.fromApi(message), ScatterbrainDatastore.Companion.DEFAULT_BLOCKSIZE)
                    .blockingAwait()
        }

        @Throws(RemoteException::class)
        override fun sendMessages(messages: List<ScatterMessage>) {
            checkAccessPermission()
            Observable.fromIterable(messages)
                    .flatMapCompletable { m: ScatterMessage ->
                        mBackend.datastore.insertAndHashFileFromApi(
                                ApiScatterMessage.Companion.fromApi(m),
                                ScatterbrainDatastore.Companion.DEFAULT_BLOCKSIZE)
                    }
                    .blockingAwait()
        }

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
                                ApiScatterMessage.Companion.fromApi(message, id),
                                ScatterbrainDatastore.Companion.DEFAULT_BLOCKSIZE)
                    }.blockingAwait()
        }

        @Throws(RemoteException::class)
        override fun startDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.start()
        }

        @Throws(RemoteException::class)
        override fun stopDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.stop()
        }

        @Throws(RemoteException::class)
        override fun startPassive() {
            checkAdminPermission()
            mBackend.radioModule.startServer()
        }

        @Throws(RemoteException::class)
        override fun stopPassive() {
            checkAdminPermission()
            mBackend.radioModule.stopServer()
        }

        @Throws(RemoteException::class)
        override fun generateIdentity(name: String): Identity {
            checkAdminPermission()
            val identity: ApiIdentity = ApiIdentity.Companion.newBuilder()
                    .setName(name)
                    .sign(ApiIdentity.Companion.newPrivateKey())
                    .build()
            return mBackend.datastore.insertApiIdentity(identity)
                    .toSingleDefault(identity)
                    .blockingGet()
        }

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

        @Throws(RemoteException::class)
        override fun isDiscovering(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isDiscovering
        }

        @Throws(RemoteException::class)
        override fun isPassive(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isPassive
        }
    }

    val packet: AdvertisePacket?
        get() = mBackend.packet

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
        try {
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
                    .setContentText("discovering peers...")
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
    }

    override fun onBind(i: Intent): IBinder? {
        super.onBind(i)
        return binder
    }

    //TODO: remove this in production
    val radioModule: BluetoothLEModule?
        get() = mBackend.radioModule

    //TODO: remove this in production
    val wifiDirect: WifiDirectRadioModule?
        get() = mBackend.wifiDirect

    //TODO: remove this in production
    val datastore: ScatterbrainDatastore?
        get() = mBackend.datastore

    override fun onUnbind(i: Intent): Boolean {
        super.onUnbind(i)
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mBackend.radioModule.stopDiscover()
        mBackend.scheduler.stop()
        mBackend.wifiDirect.unregisterReceiver()
    }

    inner class ScatterBinder : Binder() {
        val service: ScatterRoutingService
            get() = this@ScatterRoutingService
    }

    companion object {
        const val PROTO_VERSION = 0
        private val component = BehaviorRelay.create<RoutingServiceComponent>()
        private const val NOTIFICATION_CHANNEL_FOREGROUND = "foreground"
        const val PERMISSION_DENIED_STR = "permission denied"
        fun getComponent(): Single<RoutingServiceComponent> {
            return component.firstOrError()
        }
    }
}