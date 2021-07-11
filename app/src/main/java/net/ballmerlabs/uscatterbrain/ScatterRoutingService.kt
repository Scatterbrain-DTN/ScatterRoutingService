package net.ballmerlabs.uscatterbrain

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayList

/**
 * Main foreground service class for Scatterbrain.
 */
class ScatterRoutingService : LifecycleService() {
    private lateinit var mBackend: RoutingServiceBackend
    data class Callback(
            val packageName: String,
            val disposable: Disposable
    )
    private val callbackHandles = ConcurrentHashMap<Int, Callback>()
    private val callbackNum = AtomicReference(0)
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
                if (packageName != null) {
                    val disp = mBackend.datastore.updatePackage(packageName).subscribe(
                            { Log.v(TAG, "updated package name $packageName") },
                            { err -> Log.e(TAG, "failed to update package $packageName : $err") }
                    )
                }
                return packageName ?: ""
            }

        // fail if access permission is not granted
        @Throws(RemoteException::class)
        private fun checkAccessPermission() {
            if (checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
                return
            }
            if (!checkPermission(ScatterbrainApi.PERMISSION_ACCESS)) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        // fail if admin permission is not granted
        @Throws(RemoteException::class)
        private fun checkAdminPermission() {
            if (checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
                return
            }
            if (!checkPermission(ScatterbrainApi.PERMISSION_ADMIN)) {
                throw RemoteException(PERMISSION_DENIED_STR)
            }
        }

        //fail if superuser permission is not granted
        @Throws(RemoteException::class)
        private fun checkSuperuserPermission() {
            if (!checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
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
            return mBackend.datastore.getApiMessages(application).blockingGet()
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
        override fun getIdentityByFingerprint(fingerprint: ParcelUuid): Identity {
            checkAccessPermission()
            return mBackend.datastore.getApiIdentityByFingerprint(fingerprint.uuid)
        }

        /**
         * enqueues a message onto the datastore.
         * @param message
         */
        @Throws(RemoteException::class)
        override fun sendMessage(message: ScatterMessage) {
            checkAccessPermission()
            mBackend.sendMessage(message, callingPackageName).blockingAwait()
        }

        /**
         * enqueues a list of messages onto the datastore
         * @param messages list of messages
         */
        @Throws(RemoteException::class)
        override fun sendMessages(messages: List<ScatterMessage>) {
            checkAccessPermission()
            mBackend.sendMessages(messages, callingPackageName).blockingAwait()
        }

        /**
         * signs arbitrary data with a scatterbrain identity.
         * @param data data to sign
         * @param identity fingerprint of identiy to sign with
         * @throws RemoteException if application is not authorized to use identity or if
         * identity does not exist
         */
        override fun signDataDetached(data: ByteArray, identity: ParcelUuid): ByteArray {
            return mBackend.signDataDetached(data, identity.uuid, callingPackageName).blockingGet()
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
        override fun sendAndSignMessage(message: ScatterMessage, identity: ParcelUuid) {
            mBackend.sendAndSignMessage(message, identity.uuid, callingPackageName).blockingAwait()
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
        override fun generateIdentity(name: String, callback: IdentityCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.generateIdentity(name, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { res -> callback.onIdentity(listOf(res)) },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * removes an identity by fingerprint.
         * @param identity fingerprint of identity to remove
         * @return true if identity removed, false otherwise
         */
        @Throws(RemoteException::class)
        override fun removeIdentity(identity: ParcelUuid): Boolean {
            return mBackend.removeIdentity(identity.uuid, callingPackageName)
                    .toSingleDefault(true)
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
        override fun authorizeApp(identity: ParcelUuid, packagename: String) {
            checkSuperuserPermission()
            mBackend.authorizeApp(identity.uuid, packagename).blockingAwait()
        }

        /**
         * deauthorizes an app by package name to use a specific identity
         * @param identity fingerprint of identity to deauthorize
         * @param packagename package name of app
         * @throws RemoteException if package name not found or if permission denied
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(RemoteException::class)
        override fun deauthorizeApp(identity: ParcelUuid, packagename: String) {
            checkSuperuserPermission()
            mBackend.deauthorizeApp(identity.uuid, packagename).blockingAwait()
        }

        /**
         * returns a list of packages by package name that are authorized to use an identity
         * @param identity identity by fingerprint
         * @return list of package names if any
         */
        override fun getAppPermissions(identity: ParcelUuid): Array<String> {
            checkSuperuserPermission()
            return mBackend.datastore.getACLs(identity.uuid)
                    .flatMapObservable { acl -> Observable.fromIterable(acl) }
                    .map { acl -> acl.packageName }
                    .reduce(ArrayList<String>(), { list, acl ->
                        list.add(acl)
                        return@reduce list
                    })
                    .blockingGet()
                    .toTypedArray()
        }

        override fun getKnownPackages(): MutableList<String> {
            checkSuperuserPermission()
            return mBackend.datastore.getPackages().blockingGet().toMutableList()
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

        override fun clearDatastore() {
            checkSuperuserPermission()
            mBackend.datastore.clear()
        }

        override fun ping(callback: UnitCallback) {
            callback.onComplete()
        }

        override fun getByApplicationDate(application: String, startDate: Long, endDate: Long): MutableList<ScatterMessage> {
            checkAccessPermission()
            return mBackend.datastore.getApiMessagesReceiveDate(application, Date(startDate), Date(endDate)).blockingGet()
        }

        override fun getByApplicationAsync(application: String, callback: ScatterMessageCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getApiMessages(application)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { res -> callback.onScatterMessage(res) },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun getByApplicationDateAsync(application: String, startDate: Long, endDate: Long, callback: ScatterMessageCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getApiMessagesReceiveDate(application, Date(startDate), Date(endDate))
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { res -> callback.onScatterMessage(res) },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun signDataDetachedAsync(data: ByteArray, identity: ParcelUuid, callback: ByteArrayCallback) {
            checkAdminPermission()
            val handle = generateNewHandle()
            val disp = mBackend.signDataDetached(data, identity.uuid, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { res -> callback.onData(res) },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun sendMessagesAsync(messages: MutableList<ScatterMessage>, callback: UnitCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.sendMessages(messages, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { callback.onComplete() },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun sendMessageAsync(message: ScatterMessage, callback: UnitCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.sendMessage(message, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { callback.onComplete() },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun sendAndSignMessageAsync(message: ScatterMessage, identity: ParcelUuid, callback: UnitCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.sendAndSignMessage(message, identity.uuid, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { callback.onComplete() },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun sendAndSignMessagesAsync(message: MutableList<ScatterMessage>, identity: ParcelUuid, callback: UnitCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.sendAndSignMessages(message, identity.uuid, callingPackageName)
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { callback.onComplete() },
                            { err -> callback.onError(err.message) }
                    )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }


        override fun getKnownPackagesAsync(): Int {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getPackages()
                    .doOnDispose { callbackHandles.remove(handle) }
                    .doFinally { callbackHandles.remove(handle) }
                    .subscribe(
                            { r ->
                                broadcastAsyncResult(
                                        callingPackageName,
                                        handle,
                                        Bundle().apply {
                                            putStringArrayList(ScatterbrainApi.EXTRA_ASYNC_RESULT, r)
                                        },
                                        ScatterbrainApi.PERMISSION_SUPERUSER
                                )
                            },
                            { err ->
                                broadcastAsyncError(
                                        callingPackageName,
                                        handle,
                                        err.toString(),
                                        ScatterbrainApi.PERMISSION_SUPERUSER
                                )
                            }
                    )

            callbackHandles[handle] = Callback(callingPackageName, disp)
            return handle
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

    @Synchronized
    private fun generateNewHandle(): Int {
        val old = callbackNum.get()
        var n = old + 1
        if (n > Int.MAX_VALUE)
            n = 0
        callbackNum.set(n)
        return n
    }

    private inline fun <reified T: Parcelable> broadcastAsyncResult(
            packageName: String,
            handle: Int,
            result: T,
            permission: String
    ) {
        Intent().also { intent ->
            intent.action = ScatterbrainApi.BROADCAST_RESULT
            intent.`package` = packageName
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_RESULT, Bundle().apply {
                putParcelable(ScatterbrainApi.EXTRA_ASYNC_RESULT, result)
            })
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_HANDLE, handle)
            applicationContext.sendBroadcast(intent, permission)
        }
    }

    private fun broadcastAsyncResult(
            packageName: String,
            handle: Int,
            result: ByteArray,
            permission: String
    ) {
        Intent().also { intent ->
            intent.action = ScatterbrainApi.BROADCAST_RESULT
            intent.`package` = packageName
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_RESULT, Bundle().apply {
                putByteArray(ScatterbrainApi.EXTRA_ASYNC_RESULT, result)
            })
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_HANDLE, handle)
            applicationContext.sendBroadcast(intent, permission)
        }
    }

    private fun broadcastAsyncResult(packageName: String, handle: Int, permission: String) {
        Intent().also { intent ->
            intent.action = ScatterbrainApi.BROADCAST_RESULT
            intent.`package` = packageName
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_HANDLE, handle)
            applicationContext.sendBroadcast(intent, permission)
        }
    }

    private fun broadcastAsyncError(packageName: String, handle: Int, message: String, permission: String) {
        Intent().also { intent ->
            intent.action = ScatterbrainApi.BROADCAST_ERROR
            intent.`package` = packageName
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_RESULT, message)
            intent.putExtra(ScatterbrainApi.EXTRA_ASYNC_HANDLE, handle)
            applicationContext.sendBroadcast(intent, permission)
        }
    }

    /*
     * create dagger components when we have access to context
     */
    override fun onCreate() {
        super.onCreate()
        val c = DaggerRoutingServiceComponent.builder()
                .applicationContext(this)
                ?.build()!!
        component.accept(c)
        mBackend = c.scatterRoutingService()!!
    }

    /*
     * we initialize the service on start instead of on bind since multiple clients
     * may be bound at once
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_FOREGROUND,
                        "fmef",
                        NotificationManager.IMPORTANCE_DEFAULT
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
            val notificationIntent = Intent(this, ScatterRoutingService::class.java)
            val pendingIntent = PendingIntent.getService(this, 0, notificationIntent, 0)
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_FOREGROUND)
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
        const val PROTO_VERSION = 8
        const val TAG = "ScatterRoutingService"
        private val component = BehaviorRelay.create<RoutingServiceComponent>()
        private const val NOTIFICATION_CHANNEL_FOREGROUND = "foreground"
        const val PERMISSION_DENIED_STR = "permission denied"
        fun getComponent(): Single<RoutingServiceComponent> {
            return component.firstOrError()
        }
    }
}