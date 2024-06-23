package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.MaybeSubject
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent
import net.ballmerlabs.uscatterbrain.WifiDirectProvider
import net.ballmerlabs.uscatterbrain.WifiGroupSubcomponent
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.scatterproto.Optional
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Transport layer radio module for wifi direct. Currently this module only supports
 * data transfer, not device discovery. This is because mdns over wifi direct takes
 * a very long time and does not support hardware offloading
 *
 * connections are established by a group username and PSK exchanged out-of-band by
 * the previous transport layer. The SEME is the group owner and any number of UKEs
 * may join the group. Currently only one is supported though because of limitations imposed by
 * bluetooth LE.
 * TODO: wait for multiple LE handshakes and batch bootstrap requests maybe?
 *
 * Manually setting the group passphrase requires a very new API level (android 10 or above)
 */
@Singleton
class WifiDirectRadioModuleImpl @Inject constructor(
    private val mContext: Context,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) private val timeoutScheduler: Scheduler,
    private val mBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val firebaseWrapper: FirebaseWrapper = MockFirebaseWrapper(),
    private val infoComponentProvider: Provider<WifiDirectInfoSubcomponent.Builder>,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val serverSocketManager: ServerSocketManager,
    private val manager: WifiManager,
    private val advertiser: Advertiser,
    private val scheduler: Provider<ScatterbrainScheduler>,
    private val provider: WifiDirectProvider,
    private val leState: Provider<LeState>
) : WifiDirectRadioModule {
    private val LOG by scatterLog()

    private val groupDisposable = AtomicReference<CompositeDisposable?>(null)

    fun createGroupSingle(band: Int): Single<WifiDirectInfo> {
        val res = Single.defer {
            val channel = provider.getChannel()!!
            val subject = CompletableSubject.create()
            try {
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        LOG.w("successfully created group!")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        LOG.e("failed to create group: ${reasonCodeToString(reason)}")
                        subject.onError(
                            IllegalStateException(
                                "failed to create group ${
                                    reasonCodeToString(
                                        reason
                                    )
                                }"
                            )
                        )
                    }
                }

                subject.andThen(mBroadcastReceiver.observeConnectionInfo())
                    .doOnError { err -> LOG.e("createGroupSingle error: $err") }
                    .doOnNext { v -> LOG.v("waiting for group creation ${v.groupFormed} ${v.isGroupOwner} ${v.groupOwnerAddress}") }
                    .takeUntil { wifiP2pInfo ->
                        wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner && wifiP2pInfo.groupOwnerAddress != null
                    }.doOnComplete { LOG.w("createGroupSingle return success") }
                    .mergeWith(Completable.fromAction {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val builder = infoComponentProvider.get()
                            val pass = ByteArray(8)
                            LibsodiumInterface.sodium.randombytes_buf(pass, pass.size)
                            val base64pass = android.util.Base64.encodeToString(
                                pass,
                                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                            )
                            val newpass = base64pass.replace("-", "b")
                            val uuid = abs(Random().nextInt()) % 4096
                            LOG.w("createGroup with band ${FakeWifiP2pConfig.bandToStr(band)} $newpass $uuid")
                            val fakeConfig = builder.fakeWifiP2pConfig(
                                WifiDirectInfoSubcomponent.WifiP2pConfigArgs(
                                    passphrase = newpass, networkName = "DIRECT-$uuid", band = band
                                )
                            ).build()!!.fakeWifiP2pConfig()
                            provider.getManager()
                                ?.createGroup(channel, fakeConfig.asConfig(), listener)
                        } else {
                            provider.getManager()?.createGroup(channel, listener)
                        }
                        // provider.getManager()?.createGroup(provider.getChannel()?, listener)
                    }).lastOrError()
            } catch (exc: SecurityException) {
                Single.error(exc)
            }
        }
        return res
    }

    override fun registerReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)

        // Indicates a change in the list of available peers.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)

        // Indicates the state of Wi-Fi P2P connectivity has changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)

        // Indicates this device's details have changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        mContext.registerReceiver(mBroadcastReceiver.asReceiver(), intentFilter)

    }


    override fun unregisterReceiver() {
        LOG.v("unregistering broadcast receier")
        try {
            mContext.unregisterReceiver(mBroadcastReceiver.asReceiver())
        } catch (illegalArgumentException: IllegalArgumentException) {
            //firebaseWrapper.recordException(illegalArgumentException)
            LOG.w("attempted to unregister nonexistent receiver, ignore.")
        }
    }


    private fun requestGroupInfo(): Maybe<WifiP2pGroup> {
        return Maybe.defer {
            val channel = provider.getChannel()!!
            LOG.v("requestGroupInfo")
            val subject = MaybeSubject.create<WifiP2pGroup>()
            val listener = WifiP2pManager.GroupInfoListener { groupInfo ->
                if (groupInfo == null) {
                    subject.onComplete()
                } else {
                    subject.onSuccess(groupInfo)
                }
            }
            try {
                provider.getManager()!!.requestGroupInfo(channel, listener)
            } catch (exc: SecurityException) {
                firebaseWrapper.recordException(exc)
                subject.onError(exc)
            }
            subject
        }.doOnSuccess { LOG.v("got groupinfo on request") }
            .doOnComplete { LOG.v("requestGroupInfo completed") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
    }


    private fun requestConnectionInfo(): Maybe<WifiDirectInfo> {
        return Maybe.defer {
            val channel = provider.getChannel()!!
            LOG.v("requestConnectionInfo")
            val subject = MaybeSubject.create<WifiP2pInfo>()
            val listener = WifiP2pManager.ConnectionInfoListener { connectionInfo ->
                if (connectionInfo == null) {
                    subject.onComplete()
                } else {
                    subject.onSuccess(connectionInfo)
                }
            }

            try {
                provider.getManager()?.requestConnectionInfo(channel, listener)
            } catch (exc: SecurityException) {
                firebaseWrapper.recordException(exc)
                subject.onError(exc)
            }

            subject
        }.map { v ->
            WifiDirectInfo(
                groupFormed = v.groupFormed,
                isGroupOwner = v.isGroupOwner,
                groupOwnerAddress = v.groupOwnerAddress
            )
        }.doOnSuccess { LOG.v("got connectionInfo") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
            .doOnComplete { LOG.e("empty connectionInfo") }

    }

    override fun isCreatedGroup(): Boolean {
        return mBroadcastReceiver.getCurrentGroup().isEmpty.blockingGet()
    }

    override fun safeShutdownGroup(timeout: Long, timeUnit: TimeUnit): Completable {
        return mBroadcastReceiver.observePeers().takeUntil { v ->
                mBroadcastReceiver.connectedDevices().isEmpty()
            }.ignoreElements().timeout(
                timeout,
                timeUnit,
                timeoutScheduler,
                Completable.error(TimeoutException("failed to safeShutdown group"))
            ).concatWith(mBroadcastReceiver.removeCurrentGroup())
    }

    /**
     * create a wifi direct group with this device as the owner
     */
    override fun createGroup(
        band: Int,
        remoteLuid: UUID,
        selfLuid: UUID,
    ): Single<WifiGroupSubcomponent> {
        val create = requestGroupInfo().switchIfEmpty(
                createGroupSingle(band).ignoreElement().andThen(requestGroupInfo())
            ).doOnSubscribe {
                advertiser.clear(false)
            }.doOnDispose { LOG.e("createGroup disposed") }.flatMapSingle { groupInfo ->
                requestConnectionInfo().flatMapSingle { connectionInfo ->
                    LOG.e("created wifi direct group ${groupInfo.networkName} ${groupInfo.passphrase} $band")
                    mBroadcastReceiver.createCurrentGroup(band, groupInfo, connectionInfo, selfLuid)
                        .map { v ->
                            v.groupHandle().bootstrapUke(selfLuid)
                            v
                        }
                }

            }.doOnError { err: Throwable ->
                LOG.e("createGroup error $err")
            }.doFinally {
                //mBroadcastReceiver.removeCurrentGroup()
                //   advertiser.clear(true)
                //  scheduler.get().releaseWakeLock()
            }

        return create
    }

    override fun wifiDirectIsUsable(): Single<Boolean> {
        return Single.fromCallable {
            manager.isWifiEnabled && manager.isP2pSupported
        }
    }

    override fun removeGroup(retries: Int, delay: Int): Completable {
        return Completable.defer {
            LOG.v("removeGroup called")
            val channel = provider.getChannel()!!
            val subject = CompletableSubject.create()
            val actionListener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    LOG.v("removeGroup call returned success")
                    subject.onComplete()
                }

                override fun onFailure(p0: Int) {
                    LOG.e("failed to remove group: ${reasonCodeToString(p0)}")
                    when (p0) {
                        WifiP2pManager.BUSY -> subject.onComplete()
                        else -> subject.onError(
                            IllegalStateException(
                                "failed ${
                                    reasonCodeToString(
                                        p0
                                    )
                                }"
                            )
                        )
                    }
                }

            }
            val c = subject.andThen(mBroadcastReceiver.observeConnectionInfo())
                .mergeWith(Completable.fromAction {
                    provider.getManager()?.removeGroup(channel, actionListener)
                }).doOnSubscribe { LOG.w("awaiting removeGroup") }
                .doOnError { err -> LOG.e("removeGroup error: $err") }
                .doOnNext { v -> LOG.v("removegroup saw ${v.groupFormed} ${v.isGroupOwner}") }
                .takeUntil { wifiP2pInfo -> !wifiP2pInfo.groupFormed && !wifiP2pInfo.isGroupOwner }
                .ignoreElements().doOnComplete { LOG.v("removeGroup return success") }



            requestGroupInfo().flatMap { group ->
                    if (group.isGroupOwner) c.retryDelay(5, 5)
                        .doOnError { err -> firebaseWrapper.recordException(err) }.toMaybe()
                    else requestConnectionInfo().flatMap { ci ->
                        if (ci.isGroupOwner || ci.groupFormed || ci.groupOwnerAddress != null) c.retryDelay(
                            5,
                            5
                        ).doOnError { err -> firebaseWrapper.recordException(err) }.toMaybe()
                        else Maybe.empty<WifiDirectInfo>()
                    }
                }.ignoreElement()
        }
    }

    override fun connectToGroup(
        name: String, passphrase: String, timeout: Int, band: Int
    ): Single<WifiDirectInfo> {
        LOG.w("connectToGroup $name $passphrase ${FakeWifiP2pConfig.bandToStr(band)}")
        val s = Single.defer {
            val builder = infoComponentProvider.get()
            val fakeConfig = builder.fakeWifiP2pConfig(
                WifiDirectInfoSubcomponent.WifiP2pConfigArgs(
                    passphrase = passphrase, networkName = name, band = band
                )
            ).build()!!.fakeWifiP2pConfig()
            initiateConnection(fakeConfig.asConfig()).andThen(awaitConnection(timeout).doOnSuccess {
                    LOG.v(
                        "connection awaited"
                    )
                })

        }.doOnError { err ->
            err.printStackTrace()
            firebaseWrapper.recordException(err)
        }

        return removeGroup().onErrorComplete().andThen(s)
    }

    private fun getConnected(): Optional<Int> {
        //   return FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
        val connected = manager.connectionInfo?.bssid != null
        LOG.w("getBand, 5ghz supported ${manager.is5GHzBandSupported} connected $connected")
        val freq = manager.connectionInfo?.frequency
        return if (connected && (freq in 5_150..5_350)) Optional.of(FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        else if (connected && (freq in 2_400..2_483)) Optional.of(FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
        else if (!connected) Optional.empty()
        else {
            LOG.e("wifi connected with invalid frequency $freq")
            firebaseWrapper.recordException(IllegalStateException("wifi connected with invalid frequency $freq"))
            Optional.empty()
        }
    }

    override fun getBand(): Int {
        val ret = getConnected()
        return if (ret.isPresent) {
            ret.item!!
        } else {
            FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
        }
    }

    private fun cancelConnection(): Completable {
        val cancel = Completable.defer {
            val channel = provider.getChannel()!!
            val subject = CompletableSubject.create()
            try {

                val connectListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        LOG.v("canceled wifi direct conneection")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        LOG.e(
                            "failed to cancel connection, am v sad. I cry now: " + reasonCodeToString(
                                reason
                            )
                        )
                        subject.onError(
                            IllegalStateException(
                                "failed to cancel connection: " + reasonCodeToString(
                                    reason
                                )
                            )
                        )
                    }
                }
                try {
                    provider.getManager()?.cancelConnect(channel, connectListener)
                    subject
                } catch (exc: Exception) {
                    LOG.e("wifi p2p failed to cancel connect: ${exc.message}")
                    firebaseWrapper.recordException(exc)
                    exc.printStackTrace()
                    Completable.error(exc)
                }
            } catch (e: SecurityException) {
                LOG.e("wifi p2p threw SecurityException $e")
                firebaseWrapper.recordException(e)
                return@defer Completable.error(e)
            }
        }
        return cancel.retryDelay(10, 4)
    }

    /*
     * conect using a wifip2pconfig object
     */
    private fun initiateConnection(config: WifiP2pConfig): Completable {
        val connection = Completable.defer {
            val channel = provider.getChannel()!!
            val subject = CompletableSubject.create()
            try {

                val connectListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        LOG.v("connected to wifi direct group! FMEEEEE! AM HAPPY!")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        LOG.e(
                            "failed to connect to wifi direct group, am v sad. I cry now: " + reasonCodeToString(
                                reason
                            ) + " " + reason
                        )
                        subject.onError(
                            IllegalStateException(
                                "failed to connect to group: " + reasonCodeToString(
                                    reason
                                )
                            )
                        )
                    }
                }
                try {
                    provider.getManager()?.connect(channel, config, connectListener)
                    subject
                } catch (exc: Exception) {
                    LOG.e("wifi p2p failed to connect: ${exc.message}")
                    firebaseWrapper.recordException(exc)
                    exc.printStackTrace()
                    Completable.error(exc)
                }
            } catch (e: SecurityException) {
                LOG.e("wifi p2p threw SecurityException $e")
                firebaseWrapper.recordException(e)
                Completable.error(e)
            }
        }
        return connection.retryDelay(15, 1)
    }

    /*
     * wait for the BroadcastReceiver to say we are connected to a
     * group
     */
    private fun awaitConnection(timeout: Int): Single<WifiDirectInfo> {
        return mBroadcastReceiver.observeConnectionInfo()
            .doOnNext { v -> LOG.v("awaiting wifidirect connection ${v.isGroupOwner} ${v.groupOwnerAddress}") }
            .takeUntil { info -> !info.isGroupOwner && info.groupOwnerAddress != null }
            .lastOrError().timeout(timeout.toLong(), TimeUnit.SECONDS, timeoutScheduler)
            .doOnSuccess { info -> LOG.v("connect to group returned: " + info.groupOwnerAddress) }
            .doOnError { err -> LOG.e("connect to group failed: $err") }
    }

    override fun bootstrapUke(
        band: Int,
        remoteLuid: UUID,
        selfLuid: UUID,
    ): Single<WifiDirectBootstrapRequest> {
        return Single.defer {
            mBroadcastReceiver.getCurrentGroup().switchIfEmpty(
                mBroadcastReceiver.wrapConnection(createGroup(band, remoteLuid, selfLuid))
            ).map { v -> v.request() }.doOnSuccess { v -> LOG.w("uke returned upgrade ${v.band}") }
                .doOnError { err ->
                    LOG.e("failed to get server socket: $err")
                    firebaseWrapper.recordException(err)
                }
        }
    }

    @Synchronized
    override fun bootstrapSeme(req: WifiDirectBootstrapRequest, remote: UUID) {
        LOG.w("bootstrapSeme started")
        if (groupDisposable.get() == null) {
            val disp = bootstrapSeme(
                req.name, req.passphrase, req.band, req, advertiser.getHashLuid()
            )
                .doFinally { groupDisposable.getAndSet(null)?.dispose() }
                .subscribe(
                    {}, { err ->
                LOG.e("bootstrapSeme failed $err, removing group")
                mBroadcastReceiver.removeCurrentGroup()
            })

            val cd = CompositeDisposable()
            cd.add(disp)
            groupDisposable.set(cd)
        } else {
            LOG.e("bootstrapSeme already in progress, skipping")
        }
    }

    private fun bootstrapSeme(
        name: String,
        passphrase: String,
        band: Int,
        req: WifiDirectBootstrapRequest,
        self: UUID,
    ): Completable {
        return Completable.defer {
            mBroadcastReceiver.getCurrentGroup().switchIfEmpty(Completable.defer {
                advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid())
            }.andThen(Single.defer {
                mBroadcastReceiver.wrapConnection(
                    connectToGroup(
                        name, passphrase, 35, band
                    ).retryDelay(4, 3)
                )
            }).flatMap { info ->
                    serverSocketManager.getServerSocket().flatMap { socket ->
                        mBroadcastReceiver.createCurrentGroup(req)
                    }.map { g ->
                        val disp = g.groupHandle().semeServer().subscribe()
                        groupDisposable.get()!!.add(disp)
                        g
                    }
                }.doOnError { err ->
                    LOG.w("seme error: $err")
                    err.printStackTrace()
                }

                .doFinally {
                    advertiser.clear(false)
                }).timeout(45, TimeUnit.SECONDS, timeoutScheduler)
                .flatMapCompletable { h -> h.groupHandle().bootstrapSeme(self) }
                .onErrorResumeNext { err: Throwable ->
                    LOG.w("seme error $err, dumping current group")
                    mBroadcastReceiver.removeCurrentGroup().onErrorComplete()
                        .andThen(leState.get().dumpPeers(true).onErrorComplete())
                        .andThen(leState.get().refreshPeers().onErrorComplete())
                        .andThen(Completable.error(err))
                }.doOnDispose { LOG.e("wifi direct client/seme DISPOSED") }.doFinally {
                    LOG.w("wifi direct client/seme complete")
                    //    ukes.clear()
                    // m.release()
                    scheduler.get().releaseWakeLock()
                }
        }

    }


    init {
        LOG.w("init transaction")
    }

    companion object {
        fun reasonCodeToString(reason: Int): String {
            return when (reason) {
                WifiP2pManager.BUSY -> {
                    "Busy"
                }

                WifiP2pManager.ERROR -> {
                    "Error"
                }

                WifiP2pManager.P2P_UNSUPPORTED -> {
                    "P2p unsupported"
                }

                else -> {
                    "Unknown code: $reason"
                }
            }
        }
    }
}