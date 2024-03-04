package net.ballmerlabs.uscatterbrain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.*
import net.ballmerlabs.uscatterbrain.util.initDiskLogging
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Main foreground service class for Scatterbrain.
 */
class ScatterRoutingService : LifecycleService() {
    private val LOG by scatterLog()
    private lateinit var mBackend: RoutingServiceBackend

    data class Callback(
        val packageName: String,
        val disposable: Disposable
    )

    private val callbackHandles = ConcurrentHashMap<Int, Callback>()
    private val callbackNum = AtomicReference(0)

    private val binder: ScatterbrainBinderApi.Stub = object : ScatterbrainBinderApi.Stub() {
        private fun checkPermission(permName: String): Boolean {
            val pm = applicationContext.packageManager
            return PackageManager.PERMISSION_GRANTED == pm.checkPermission(
                permName,
                callingPackageName
            )
        }

        // note: we have to get the package name from the Stub class because
        // the binder is called via a proxy that may run as a different process
        @get:Synchronized
        private val callingPackageName: String
            get() {
                var packageName: String? = null
                val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
                if (!packages.isNullOrEmpty()) {
                    packageName = packages[0]
                }
                if (packageName != null) {
                    mBackend.datastore.updatePackage(packageName).blockingAwait()
                }
                return packageName ?: ""
            }

        // fail if access permission is not granted
        @Throws(UnauthorizedException::class)
        private fun checkAccessPermission() {
            if (checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
                return
            }
            if (!checkPermission(ScatterbrainApi.PERMISSION_ACCESS)) {
                throw UnauthorizedException()
            }
        }

        // fail if admin permission is not granted
        @Throws(UnauthorizedException::class)
        private fun checkAdminPermission() {
            if (checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
                return
            }
            if (!checkPermission(ScatterbrainApi.PERMISSION_ADMIN)) {
                throw UnauthorizedException()
            }
        }

        //fail if superuser permission is not granted
        @Throws(UnauthorizedException::class)
        private fun checkSuperuserPermission() {
            if (!checkPermission(ScatterbrainApi.PERMISSION_SUPERUSER)) {
                throw UnauthorizedException()
            }
        }

        /**
         * Get scattermessage by database id.
         * @param id id unique to sqlite table
         * @return message matching id
         * @throws UnauthorizedException if message not found
         */
        @Throws(UnauthorizedException::class)
        override fun getById(id: Long): ScatterMessage {
            checkAccessPermission()
            return mBackend.datastore.getApiMessages(id)
        }


        /**
         * get all messages belonging to an application
         * @param application identifier
         * @return list of messages, may be zero
         */
        @Throws(UnauthorizedException::class)
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
        @Throws(UnauthorizedException::class)
        override fun getIdentities(): List<Identity> {
            checkAccessPermission()
            return mBackend.datastore.allIdentities
        }

        /**
         * get identity matching a specific fingerprint
         * @param fingerprint
         * @return identity
         */
        @Throws(UnauthorizedException::class)
        override fun getIdentityByFingerprint(fingerprint: ParcelUuid): Identity {
            checkAccessPermission()
            return mBackend.datastore.getApiIdentityByFingerprint(fingerprint.uuid)
                .blockingGet().identity
        }

        /**
         * enqueues a message onto the datastore.
         * @param message
         */
        @Throws(UnauthorizedException::class)
        override fun sendMessage(message: ScatterMessage) {
            checkAccessPermission()
            mBackend.sendMessage(message, callingPackageName).blockingAwait()
        }

        /**
         * enqueues a list of messages onto the datastore
         * @param messages list of messages
         */
        @Throws(UnauthorizedException::class)
        override fun sendMessages(messages: List<ScatterMessage>) {
            checkAccessPermission()
            mBackend.sendMessages(messages, callingPackageName).blockingAwait()
        }

        /**
         * signs arbitrary data with a scatterbrain identity.
         * @param data data to sign
         * @param identity fingerprint of identiy to sign with
         * @throws UnauthorizedException if application is not authorized to use identity or if
         * identity does not exist
         */
        override fun signDataDetached(data: ByteArray, identity: ParcelUuid): ByteArray {
            return mBackend.signDataDetached(data, identity.uuid, callingPackageName).blockingGet()
        }

        /**
         * sends a message and signs with specified identity fingerprint
         * @param message scattermessage to send
         * @param identity fingerprint of identity to sign with
         * @throws UnauthorizedException if application is not authorized to use identity or if identity
         * does not exist
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(UnauthorizedException::class)
        override fun sendAndSignMessage(message: ScatterMessage, identity: ParcelUuid) {
            mBackend.sendAndSignMessage(message, identity.uuid, callingPackageName).blockingAwait()
        }

        /**
         * starts active discovery with one or more transport layers
         */
        @Throws(UnauthorizedException::class)
        override fun startDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.start()
        }

        /**
         * stops active discovery with all transport layers
         */
        @Throws(UnauthorizedException::class)
        override fun stopDiscovery() {
            checkAdminPermission()
            mBackend.scheduler.stop()
        }

        /**
         * starts passive listening for connections on all transports
         */
        @Throws(UnauthorizedException::class)
        override fun startPassive() {
            checkAdminPermission()
            mBackend.scheduler.start()
        }

        /**
         * stops passive listening for connections on all transports
         */
        @Throws(UnauthorizedException::class)
        override fun stopPassive() {
            checkAdminPermission()
            mBackend.scheduler.stop()
        }

        /**
         * generates and stores a new identity
         * @param name name for this identity
         * @return identity object generated
         */
        @Throws(UnauthorizedException::class)
        override fun generateIdentity(name: String, callback: IdentityCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.generateIdentity(name, callingPackageName)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .doFinally { callback.onComplete() }
                .subscribe(
                    { res -> callback.onIdentity(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }


        @Throws(UnauthorizedException::class)
        override fun getIdentity(fingerprint: ParcelUuid, callback: IdentityCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()

            val disp = mBackend.getIdentity(fingerprint.uuid)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .doFinally { callback.onComplete() }
                .subscribe(
                    { res -> callback.onIdentity(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * removes an identity by fingerprint.
         * @param identity fingerprint of identity to remove
         * @return true if identity removed, false otherwise
         */
        @Throws(UnauthorizedException::class)
        override fun removeIdentity(identity: ParcelUuid, callback: BoolCallback) {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.removeIdentity(identity.uuid, callingPackageName)
                .toSingleDefault(true)
                .onErrorReturnItem(false)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { res -> callback.onResult(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * authorizes an app by package name to use a specific identity
         * @param identity fingerprint of identity to authorize
         * @param packagename package name of app
         * @throws UnauthorizedException if package name not found or if permission denied
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(UnauthorizedException::class)
        override fun authorizeApp(
            identity: ParcelUuid,
            packagename: String,
            callback: UnitCallback
        ) {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.authorizeApp(identity.uuid, packagename)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { callback.onComplete() },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * deauthorizes an app by package name to use a specific identity
         * @param identity fingerprint of identity to deauthorize
         * @param packagename package name of app
         * @throws UnauthorizedException if package name not found or if permission denied
         */
        @RequiresApi(api = Build.VERSION_CODES.P)
        @Throws(UnauthorizedException::class)
        override fun deauthorizeApp(identity: ParcelUuid, packagename: String) {
            checkSuperuserPermission()
            mBackend.deauthorizeApp(identity.uuid, packagename).blockingAwait()
        }

        /**
         * returns a list of packages by package name that are authorized to use an identity
         * @param identity identity by fingerprint
         * @return list of package names if any
         */
        override fun getAppPermissions(identity: ParcelUuid, callback: StringCallback) {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getACLs(identity.uuid)
                .flatMapObservable { acl -> Observable.fromIterable(acl) }
                .map { acl -> acl.packageName }
                .reduce(ArrayList<String>()) { list, acl ->
                    list.add(acl)
                    return@reduce list
                }
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { s -> callback.onString(s) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * gets a list of package names that the Scatterbrain service has interacted
         * with recently
         * @return MutableList of package names
         */
        override fun getKnownPackages(): MutableList<String> {
            checkSuperuserPermission()
            return mBackend.datastore.getPackages().blockingGet().toMutableList()
        }

        /**
         * returns true if active discovery is running
         * @return is discovering
         */
        @Throws(UnauthorizedException::class)
        override fun isDiscovering(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isDiscovering
        }

        /**
         * returns true if passive listening
         * @return is passive
         */
        @Throws(UnauthorizedException::class)
        override fun isPassive(): Boolean {
            checkAccessPermission()
            return mBackend.scheduler.isPassive
        }

        /**
         * clears the entire datastore. This is a horrible idea and nothing should
         * ever call this.
         */
        override fun clearDatastore() {
            checkSuperuserPermission()
            mBackend.datastore.clear()
        }

        /**
         * Immediately fires callback, used to verify the router is running
         * @param callback callback that immediately calls onComplete()
         */
        override fun ping(callback: UnitCallback) {
            callback.onComplete()
        }

        /**
         * returns a list of all stored messages for a given application between two dates.
         * @param application application identifier
         * @param startDate start date
         * @param endDate end data
         * @return list of message objects
         */
        override fun getByApplicationDate(
            application: String,
            startDate: Long,
            endDate: Long
        ): MutableList<ScatterMessage> {
            checkAccessPermission()
            return mBackend.datastore.getApiMessagesReceiveDate(
                application,
                Date(startDate),
                Date(endDate)
            ).blockingGet()
        }

        /**
         * returns a list of all stored message objects for a given application
         * @param application application identifier
         * @param callback binder callback returning list of message objects for application
         */
        override fun getByApplicationAsync(application: String, callback: ScatterMessageCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getApiMessages(application)
                .flatMapObservable { m -> Observable.fromIterable(m) }
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .doFinally { callback.onComplete() }
                .subscribe(
                    { res -> callback.onScatterMessage(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * returns a list of all stored messages for a given application between two dates.
         * @param application application identifier
         * @param startDate start date
         * @param endDate end data
         * @param callback binder callback returning list of message objects
         */
        override fun getByApplicationDateAsync(
            application: String,
            startDate: Long,
            endDate: Long,
            callback: ScatterMessageCallback
        ) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getApiMessagesReceiveDate(
                application,
                Date(startDate),
                Date(endDate)
            )
                .flatMapObservable { m -> Observable.fromIterable(m) }
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .doFinally { callback.onComplete() }
                .subscribe(
                    { res -> callback.onScatterMessage(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun manualRefreshPeers(callback: UnitCallback) {
            checkAdminPermission()
            LOG.v("manualRefreshPeers")
            val handle = generateNewHandle()
            val disp = mBackend.refreshPeers()
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { callback.onComplete() },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * Cryptographically signs data using a stored identity.
         * @param identity identity object
         * @param data bytes to sign
         * @param callback binder callback returning detached ed25519 signature
         */
        override fun signDataDetachedAsync(
            data: ByteArray,
            identity: ParcelUuid,
            callback: ByteArrayCallback
        ) {
            checkAccessPermission()
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

        /**
         * Cryptographically verifies a detached signature using a stored identity
         * @param identity identity object to verify with
         * @param data data to verify
         * @param sig detached signature generated by sign()
         * @param callback binder callback returning true if valid, false if invalid
         */
        override fun verifyDataAsync(
            data: ByteArray,
            sig: ByteArray,
            identity: ParcelUuid,
            callback: BoolCallback
        ) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = mBackend.verifyData(data, sig, identity.uuid)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { res -> callback.onResult(res) },
                    { err -> callback.onError(err.message) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * Enqueues a Scatterbrain message to the datastore. The messages will be sent as soon
         * as a peer is available
         * @param messages message to send
         * @param callback unit callback returning when complete
         */
        override fun sendMessagesAsync(
            messages: MutableList<ScatterMessage>,
            callback: UnitCallback
        ) {
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

        /**
         * Enqueues a Scatterbrain message to the datastore. The messages will be sent as soon
         * as a peer is available
         * @param message message to send
         * @param callback unit callback returning when complete
         */
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

        /**
         * Enqueues a Scatterbrain message to the datastore. The messages will be sent as soon
         * as a peer is available and will be signed by the given identity key
         * @param message message to send
         * @param identity identity fingerprint to sign with
         * @param callback unit callback returning when complete
         */
        override fun sendAndSignMessageAsync(
            message: ScatterMessage,
            identity: ParcelUuid,
            callback: UnitCallback
        ) {
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

        /**
         * Enqueues a Scatterbrain message to the datastore. The messages will be sent as soon
         * as a peer is available and will be signed by the given identity key
         * @param message message to send
         * @param identity identity fingerprint to sign with
         * @param callback unit callback returning when complete
         */
        override fun sendAndSignMessagesAsync(
            message: MutableList<ScatterMessage>,
            identity: ParcelUuid,
            callback: UnitCallback
        ) {
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


        override fun getPermissionsGranted(callback: PermissionCallback) {
            checkAccessPermission()
            val handle = generateNewHandle()
            val disp = Single.fromCallable {
                val map = HashMap<String, Boolean>()
                val advertise = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ActivityCompat.checkSelfPermission(
                        applicationContext, Manifest.permission.BLUETOOTH_ADVERTISE
                    ) == PackageManager.PERMISSION_GRANTED
                else
                    true
                map[PermissionStatus.PERMISSION_BLUETOOTH_ADVERTISE] = advertise

                val connect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ActivityCompat.checkSelfPermission(
                        applicationContext, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                else
                    true
                map[PermissionStatus.PERMISSION_BLUETOOTH_CONNECT] = connect

                val location = ActivityCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                        || ActivityCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                map[PermissionStatus.PERMISSION_LOCATION] = location
                PermissionStatus(map)
            }
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { r -> callback.onPermission(r) },
                    { e -> callback.onError(e.toString()) }
                )
            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        override fun exportDatabase(uri: Uri, callback: UnitCallback) {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.dumpDatastore(uri)
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { callback.onComplete() },
                    { e -> callback.onError(e.toString()) }
                )

            callbackHandles[handle] = Callback(callingPackageName, disp)
        }

        /**
         * Gets a list of packages this service has interacted with
         */
        override fun getKnownPackagesAsync(callback: StringCallback) {
            checkSuperuserPermission()
            val handle = generateNewHandle()
            val disp = mBackend.datastore.getPackages()
                .doOnDispose { callbackHandles.remove(handle) }
                .doFinally { callbackHandles.remove(handle) }
                .subscribe(
                    { r -> callback.onString(r) },
                    { err -> callback.onError(err.message) }
                )

            callbackHandles[handle] = Callback(callingPackageName, disp)
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

    /*
     * create dagger components when we have access to context
     */
    override fun onCreate() {
        super.onCreate()
        LOG.e("ScatterRoutingService onCreate called (should only call this once)")
        if (!this::mBackend.isInitialized) {
            initDiskLogging()
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_FOREGROUND,
                "fmef",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            LOG.e("init!!")
            val c = this.getComponent()!!
            component.accept(c)
            mBackend = c.scatterRoutingService()
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /*
     * we initialize the service on start instead of on bind since multiple clients
     * may be bound at once
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_FOREGROUND)
            .setContentTitle("ScatterRoutingService")
            .setContentText("discovering peers...\n(this uses location permission, but not actual geolocation)")
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setTicker("fmef am tire")
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            startForeground(1, notification)
        } else {
            throw SecurityException("failed to start due to missing permission")
        }
        try {
            mBackend.leState.connectionCache.clear()

        } catch (e: Exception) {
            e.printStackTrace()
            FirebaseCrashlytics.getInstance().recordException(e)
            LOG.e("exception when registering receiver $e")
            throw e
        }

        return Service.START_STICKY
    }

    /* make sure to trigger disposal of any rxjava chains before shutting down */
    override fun onDestroy() {
        super.onDestroy()
        LOG.e("onDestroy called")
        mBackend.scheduler.stop()
        // mBackend.radioModule.clearPeers()
        //mBackend.wifiDirect.unregisterReceiver()
    }

    companion object {
        private val component = BehaviorRelay.create<RoutingServiceComponent>()
        private const val NOTIFICATION_CHANNEL_FOREGROUND = "foreground"
        fun getComponent(): Single<RoutingServiceComponent> {
            return component.firstOrError()
        }
    }
}

val component = AtomicReference<RoutingServiceComponent?>(null)

fun Context.getComponent(): RoutingServiceComponent? {
    return component.updateAndGet { c ->
        c ?: DaggerRoutingServiceComponent
            .builder()
            .applicationContext(this)?.build()
    }!!

}
