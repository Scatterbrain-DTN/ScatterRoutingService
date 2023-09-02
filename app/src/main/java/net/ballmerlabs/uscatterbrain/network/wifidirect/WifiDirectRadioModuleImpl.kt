package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import io.reactivex.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.MaybeSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.getMutex
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

data class GroupHandle(
    val stream: Flowable<Pair<UUID, DisposableSocket>>,
    val bootstrap: WifiDirectBootstrapRequest
)

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
    private val datastore: ScatterbrainDatastore,
    private val preferences: RouterPreferences,
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val timeoutScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val readScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.WIFI_WRITE) private val writeScheduler: Scheduler,
    private val mBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val firebaseWrapper: FirebaseWrapper = MockFirebaseWrapper(),
    private val infoComponentProvider: Provider<WifiDirectInfoSubcomponent.Builder>,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val serverSocketManager: ServerSocketManager,
    private val socketProvider: SocketProvider,
    private val manager: WifiManager,
    private val advertiser: Advertiser,
    private val leState: Provider<LeState>,
    private val scheduler: Provider<ScatterbrainScheduler>,
    private val provider: WifiDirectProvider
) : WifiDirectRadioModule {
    private val LOG by scatterLog()

    private val connectedPeers = ConcurrentHashMap<InetSocketAddress, InetSocketAddress>()
    private val connectedAddressSet = ConcurrentHashMap<InetSocketAddress, UUID>()
    private val createGroupCache = AtomicReference<BehaviorSubject<WifiDirectBootstrapRequest>>()
    private val connects = ConcurrentHashMap<Disposable, Boolean>()
    private val altUke = BehaviorSubject.create<Pair<UUID, UpgradePacket>>()

    override fun awaitUke(): Observable<Pair<UUID, UpgradePacket>> {
        return altUke
    }

    override fun setUke(ukes: Map<UUID, UpgradePacket>): Completable {
        advertiser.ukes.clear()
        advertiser.ukes.putAll(ukes)
        return advertiser.setAdvertisingLuid(ukes = ukes)
    }

    override fun addUke(uuid: UUID, bootstrap: UpgradePacket): Completable {
        altUke.onNext(Pair(uuid, bootstrap))
        advertiser.ukes[uuid] = bootstrap
        return advertiser.setAdvertisingLuid(ukes = advertiser.ukes)
    }

    override fun removeUke(uuid: UUID) {
        advertiser.ukes.remove(uuid)
        // advertiser.setUkes(ukes).blockingAwait()
    }

    override fun getUkes(): Map<UUID, UpgradePacket> {
        return advertiser.ukes.toMap()
    }

    private fun updateConnectedPeers() {
        connectedPeers.clear()
        for (v in connectedAddressSet.keys) {
            for (u in connectedAddressSet.keys) {
                if (v.address != u.address
                    && !(connectedPeers.containsKey(v) && connectedPeers.contains(u))
                    && !(connectedPeers.containsKey(u) && connectedPeers.contains(v))
                ) {
                    connectedPeers[v] = u
                }
            }
        }
    }

    private fun createGroupSingle(band: Int): Single<WifiDirectInfo> {
        return Single.defer {
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
                            LOG.w("createGroup with band $band $newpass")
                            val fakeConfig = builder.fakeWifiP2pConfig(
                                WifiDirectInfoSubcomponent.WifiP2pConfigArgs(
                                    passphrase = newpass,
                                    networkName = "DIRECT-sb",
                                    band = band
                                )
                            ).build()!!.fakeWifiP2pConfig()
                            provider.getManager()?.createGroup(channel, fakeConfig.asConfig(), listener)
                        } else {
                            provider.getManager()?.createGroup(channel, listener)
                        }
                       // provider.getManager()?.createGroup(provider.getChannel()?, listener)
                    })
                    .doOnError { err -> LOG.e("createGroupSingle error: $err") }
                    .takeUntil { wifiP2pInfo ->
                        wifiP2pInfo.groupFormed() && wifiP2pInfo.isGroupOwner() && wifiP2pInfo.groupOwnerAddress() != null
                    }
                    .doOnComplete { LOG.w("createGroupSingle return success") }
                    .firstOrError()
            } catch (exc: SecurityException) {
                Single.error(exc)
            }
        }

    }

    override fun registerReceiver() {

    }


    override fun unregisterReceiver() {
        /*
        LOG.v("unregistering broadcast receier")
        try {
            mContext.unregisterReceiver(mBroadcastReceiver.asReceiver())
        } catch (illegalArgumentException: IllegalArgumentException) {
            //firebaseWrapper.recordException(illegalArgumentException)
            LOG.w("attempted to unregister nonexistent receiver, ignore.")
        }

         */
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
                provider.getManager()?.requestGroupInfo(channel, listener)
            } catch (exc: SecurityException) {
                firebaseWrapper.recordException(exc)
                subject.onError(exc)
            }
            subject
        }
            .doOnSuccess { LOG.v("got groupinfo on request") }
            .doOnComplete { LOG.v("requestGroupInfo completed") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
    }


    private fun requestConnectionInfo(): Maybe<WifiP2pInfo> {
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
        }.doOnSuccess { LOG.v("got connectionInfo") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
            .doOnComplete { LOG.e("empty connectionInfo") }

    }

    private fun createGroupDryRun(band: Int): Completable {
        return requestGroupInfo()
            .switchIfEmpty(
                createGroupSingle(band)
                    .ignoreElement()
                    .toMaybe()
            ).ignoreElement()
    }


    private fun sendConnectedIps(sock: Socket, selfLuid: UUID): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder(selfLuid)
            LOG.e("sendConnectedIps ${connectedPeers.size} ${sock.localAddress}")
            //    builder.addAddress(advertiser.getHashLuid(), InetSocketAddress(sock.localAddress, sock.localPort))
            connectedPeers
                .filter { v -> v.key.address == sock.localAddress }
                .forEach { v -> builder.addAddress(connectedAddressSet[v.value]!!, v.value) }
            builder.build()
                .writeToStream(sock.getOutputStream(), writeScheduler)
                .andThen(
                    ScatterSerializable.parseWrapperFromCRC(
                        IpAnnouncePacket.parser(),
                        sock.getInputStream(),
                        readScheduler
                    )
                )
                .map { p ->
                    p.addresses.forEach { addr ->
                        connectedAddressSet[addr.component2().address] = addr.component1()
                    }
                    updateConnectedPeers()
                    p
                }
        }.doOnSuccess { LOG.e("sendConnectedIps complete") }
    }


    private fun sendSelfIp(socket: Socket, self: UUID, port: Int): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder(self)
                .addAddress(advertiser.getHashLuid(), InetSocketAddress(socket.localAddress, port))
                .build()

            ScatterSerializable.parseWrapperFromCRC(
                IpAnnouncePacket.parser(),
                socket.getInputStream(),
                readScheduler
            )
                .toObservable()
                .mergeWith(
                    builder.writeToStream(socket.getOutputStream(), writeScheduler)
                )
                .firstOrError()

        }
    }

    override fun safeShutdownGroup(): Completable {
        return mBroadcastReceiver.observePeers()
            .takeUntil { v ->
                val np = isNoPeers(v)
                val newnp = mBroadcastReceiver.connectedDevices()
                    .isEmpty()
                np && newnp
            }.ignoreElements()
            .concatWith(removeGroup())
    }

    /**
     * create a wifi direct group with this device as the owner
     */
    override fun createGroup(
        band: Int,
        remoteLuid: UUID,
        selfLuid: UUID,
    ): Flowable<WifiDirectBootstrapRequest> {
        val create = retryDelay(
            retryDelay(createGroupSingle(band).ignoreElement(), 10, 1)
                .andThen(retryDelay(requestGroupInfo().toSingle(), 10, 1)), 10, 1
        )
            .doOnDispose { LOG.e("createGroup disposed") }
            .flatMapPublisher { groupInfo ->
                LOG.e("created wifi direct group ${groupInfo.networkName} ${groupInfo.passphrase}")
                serverSocketManager.getServerSocket().flatMapPublisher { serverSocket ->
                    LOG.v("got socket ${serverSocket.socket.localPort}")
                    val request = bootstrapRequestProvider.get()
                        .wifiDirectArgs(
                            BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                                passphrase = groupInfo.passphrase,
                                name = groupInfo.networkName,
                                role = BluetoothLEModule.Role.ROLE_UKE,
                                band = band,
                                port = serverSocket.socket.localPort
                            )
                        ).build()!!.wifiBootstrapRequest()
                    serverSocket.accept()
                        .repeat()
                        .retry()
                        .subscribeOn(timeoutScheduler)
                        .mergeWith(Completable.defer {
                            advertiser.clear(false)
                            advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid())
                        })
                        .doOnCancel { LOG.e("createGroup canceled") }
                        .mergeWith(
                            addUke(
                                advertiser.getHashLuid(),
                                request.toUpgrade(Random().nextInt())
                            )
                        )
                        .doOnError { err -> LOG.w("uke socket error $err, probably just a disconnect") }
                        .flatMapSingle { sock ->
                            sendConnectedIps(sock.socket, selfLuid)
                                .ignoreElement()
                                .andThen(bootstrapUkeSocket(sock.socket)
                                    .doOnError { err -> LOG.w("uke bootstrapUkeSocket failed $err") })
                                .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
                        }.subscribeOn(timeoutScheduler)
                        .flatMap { Flowable.never<WifiDirectBootstrapRequest>() }
                        .mergeWith(Flowable.just(request))
                        .materialize()
                        .mergeWith(mBroadcastReceiver.observePeers()
                            .takeUntil { p -> p.deviceList.isNotEmpty() }
                            .ignoreElements()
                            .andThen(
                                mBroadcastReceiver.observePeers()
                                    .delay(60, TimeUnit.SECONDS, timeoutScheduler)
                                    .takeUntil { v ->
                                        val np = isNoPeers(v)
                                        val newnp = mBroadcastReceiver.connectedDevices()
                                            .isEmpty()
                                        LOG.e("checking connected peers: $np, $newnp")
                                        np && newnp
                                    }

                            )
                            .doOnComplete { LOG.e("Stopping uke server due to no peers") }
                            .ignoreElements()
                            .materialize()
                        )
                        .dematerialize<WifiDirectBootstrapRequest>()
                        .doFinally {
                            LOG.v("uke server complete")
                        }
                }
            }
            .doOnCancel { LOG.e("createGroup canceled") }
            .doOnComplete { LOG.e("createGroup completed") }
            .concatWith(Completable.defer {
                advertiser.clear(true)
                advertiser.ukes.clear()
                advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid(), ukes = mapOf())
            })
            .onErrorResumeNext { err: Throwable ->
                advertiser.clear(true)
                advertiser.ukes.clear()
                advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid(), ukes = mapOf())
                    .andThen(Flowable.error(err))
            }
            .doOnError { err: Throwable ->
                LOG.e("createGroup error $err")
                connects.forEach { (v, _) ->
                    v.dispose()
                }
            }
            .doFinally {
                leState.get().connectionCache.forEach { (t, u) ->
                    //leState.get().updateDisconnected(t)
                    //leState.get().updateGone(t)
                }
                scheduler.get().releaseWakeLock()
            }

        return retryDelay(create, 10, 5)
    }


    private fun isNoPeers(v: WifiP2pDeviceList): Boolean {
        LOG.v("createGroup sees peerlist at ${v.deviceList.size} ${connectedAddressSet.size}")
        for (peer in connectedAddressSet) {
            if (!v.deviceList.map { v -> v.deviceAddress }
                    .contains(peer.key.address.toString())) {
                LOG.e("peer disconnected, removing")
                connectedAddressSet.remove(peer.key)
            }
        }
        return v.deviceList.isEmpty() && connectedAddressSet.isEmpty()
    }

    override fun getForceUke(): Boolean {
        return createGroupCache.get() != null
    }

    override fun wifiDirectIsUsable(): Single<Boolean> {
        return Single.just(true)
        return createGroupDryRun(getBand())
            .doOnError { err ->
                LOG.e("cry $err")
                err.printStackTrace()
            }
            .timeout(10, TimeUnit.SECONDS)
            .toSingleDefault(true)
            .onErrorReturnItem(false)
    }

    override fun removeGroup(retries: Int, delay: Int): Completable {
        val c = Completable.defer {
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
                    subject.onError(IllegalStateException("failed ${reasonCodeToString(p0)}"))
                }

            }
            subject.andThen(mBroadcastReceiver.observeConnectionInfo())
                .mergeWith(Completable.fromAction {
                    provider.getManager()?.removeGroup(channel, actionListener)
                })
                .doOnSubscribe { LOG.w("awaiting removeGroup") }
                .doOnError { err -> LOG.e("removeGroup error: $err") }
                .takeUntil { wifiP2pInfo -> !wifiP2pInfo.groupFormed() && !wifiP2pInfo.isGroupOwner() }
                .ignoreElements()
                .doOnComplete { LOG.v("removeGroup return success") }


        }



        return requestGroupInfo()
            .isEmpty
            .flatMapCompletable { empty ->
                if (!empty)
                    retryDelay(c, 10, 5)
                        .doOnError { err -> firebaseWrapper.recordException(err) }
                else
                    Completable.complete()

            }

    }

    private fun connectToGroup(
        name: String,
        passphrase: String,
        timeout: Int,
        band: Int
    ): Single<WifiDirectInfo> {
        LOG.w("connectToGroup $name $passphrase")
        return Single.defer {
            val connection = Single.defer {
                val builder = infoComponentProvider.get()
                val fakeConfig = builder.fakeWifiP2pConfig(
                    WifiDirectInfoSubcomponent.WifiP2pConfigArgs(
                        passphrase = passphrase,
                        networkName = name,
                        band = band
                    )
                ).build()!!.fakeWifiP2pConfig()
                //TODO: potentially remove group here?
                initiateConnection(fakeConfig.asConfig())
                    .andThen(awaitConnection(timeout).doOnSuccess { LOG.v("connection awaited") })

            }.doOnError { err ->
                err.printStackTrace()
                firebaseWrapper.recordException(err)
            }

            requestConnectionInfo().flatMap { info ->
                if (info.isGroupOwner || mBroadcastReceiver.connectedDevices()
                        .isNotEmpty()
                ) {
                    LOG.w("was group owner when initiating connection, removing group")
                    removeGroup().andThen(connection.toMaybe())
                } else {
                    connection.toMaybe()
                }
            }.switchIfEmpty(connection)
        }
    }

    override fun getBand(): Int {
     //   return FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
        val connected = manager.connectionInfo?.networkId != -1
        //    LOG.w("getBand, 5ghz supported ${manager.is5GHzBandSupported} $connected")
        return if (manager.is5GHzBandSupported && !connected)
            FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        else if (manager.is5GHzBandSupported && connected)
            FakeWifiP2pConfig.GROUP_OWNER_BAND_AUTO
        else
            FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
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
        return retryDelay(cancel, 10, 4)
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
        return retryDelay(connection, 15, 1)
    }

    private fun ackBarrier(socket: Socket, success: Boolean = true): Completable {
        return AckPacket.newBuilder(success)
            .build()
            .writeToStream(socket.getOutputStream(), writeScheduler)
            .mergeWith(
                ScatterSerializable.parseWrapperFromCRC(
                    AckPacket.parser(),
                    socket.getInputStream(),
                    readScheduler
                ).ignoreElement()
            )
    }

    //transfer declare hashes packet as UKE
    private fun declareHashesUke(socket: Socket): Single<DeclareHashesPacket> {
        LOG.v("declareHashesUke")
        return declareHashesSeme(socket)
    }

    //transfer declare hashes packet as SEME
    private fun declareHashesSeme(socket: Socket): Single<DeclareHashesPacket> {
        LOG.v("declareHashesSeme")
        return datastore.declareHashesPacket
            .flatMapObservable { declareHashesPacket ->
                ScatterSerializable.parseWrapperFromCRC(
                    DeclareHashesPacket.parser(),
                    socket.getInputStream(),
                    readScheduler
                )
                    .toObservable()
                    .mergeWith(
                        declareHashesPacket.writeToStream(
                            socket.getOutputStream(),
                            writeScheduler
                        )
                    )
            }
            .firstOrError()
    }

    //transfer routing metadata packet as UKE
    private fun routingMetadataUke(
        packets: Flowable<RoutingMetadataPacket>,
        socket: Socket
    ): Observable<RoutingMetadataPacket> {
        return routingMetadataSeme(socket, packets)
    }

    //transfer routing metadata packet as SEME
    private fun routingMetadataSeme(
        socket: Socket,
        packets: Flowable<RoutingMetadataPacket>
    ): Observable<RoutingMetadataPacket> {
        return Observable.just(socket)
            .flatMap { sock ->
                packets.concatMapCompletable { p ->
                    p.writeToStream(sock.getOutputStream(), writeScheduler)
                }.toObservable<RoutingMetadataPacket>()
                    .mergeWith(
                        ScatterSerializable.parseWrapperFromCRC(
                            RoutingMetadataPacket.parser(),
                            sock.getInputStream(),
                            readScheduler
                        )
                            .toObservable()
                            .repeat()
                            .takeWhile { routingMetadataPacket ->
                                val end = !routingMetadataPacket.isEmpty
                                if (!end) {
                                    LOG.v("routingMetadata seme end of stream")
                                }
                                end
                            })

            }.doOnComplete { LOG.w("routingMetadata Complete") }
    }

    //transfer identity packet as UKE
    private fun identityPacketUke(
        packets: Flowable<IdentityPacket>,
        socket: Socket
    ): Observable<IdentityPacket> {
        return identityPacketSeme(socket, packets)
    }

    //transfer identity packet as SEME
    private fun identityPacketSeme(
        socket: Socket,
        packets: Flowable<IdentityPacket>
    ): Observable<IdentityPacket> {
        return Single.just(socket)
            .flatMapObservable { sock ->
                packets.concatMapCompletable { p ->
                    p.writeToStream(sock.getOutputStream(), writeScheduler)
                        .doOnComplete { LOG.v("wrote single identity packet") }
                }.toObservable<IdentityPacket>()
                    .mergeWith(
                    ScatterSerializable.parseWrapperFromCRC(
                        IdentityPacket.parser(),
                        sock.getInputStream(),
                        readScheduler
                    )
                        .toObservable()
                        .repeat()
                        .takeWhile { identityPacket ->
                            val end = !identityPacket.isEnd
                            if (!end) {
                                LOG.v("identitypacket seme end of stream")
                            }
                            end
                        })


            }.doOnComplete { LOG.w("identity packets complete") }
    }

    /*
     * wait for the BroadcastReceiver to say we are connected to a
     * group
     */
    private fun awaitConnection(timeout: Int): Single<WifiDirectInfo> {
        return mBroadcastReceiver.observeConnectionInfo()
            .doOnNext { v -> LOG.v("awaiting wifidirect connection ${v.isGroupOwner()} ${v.groupOwnerAddress()}") }
            .takeUntil { info -> !info.isGroupOwner() && info.groupOwnerAddress() != null }
            .lastOrError()
            .timeout(timeout.toLong(), TimeUnit.SECONDS, timeoutScheduler)
            .doOnSuccess { info -> LOG.v("connect to group returned: " + info.groupOwnerAddress()) }
            .doOnError { err -> LOG.e("connect to group failed: $err") }
    }

    private fun bootstrapUkeSocket(socket: Socket): Single<HandshakeResult> {
        return Single.defer {
            routingMetadataUke(
                Flowable.just(
                    RoutingMetadataPacket.newBuilder().setEmpty().build()
                ),
                socket
            )
                .ignoreElements()
                .andThen(
                    identityPacketUke(datastore.getTopRandomIdentities(20), socket)
                        .reduce(
                            ArrayList()
                        ) { list: ArrayList<IdentityPacket>, packet: IdentityPacket ->
                            list.add(packet)
                            list
                        }.flatMap { p: ArrayList<IdentityPacket> ->
                            datastore.insertIdentityPacket(p).toSingleDefault(
                                HandshakeResult(
                                    p.size,
                                    0,
                                    HandshakeResult.TransactionStatus.STATUS_SUCCESS
                                )
                            )
                        }
                ).flatMap { stats ->
                    declareHashesUke(socket)
                        .doOnSuccess {
                            LOG.v("received declare hashes packet uke")
                        }
                        .flatMap { declareHashesPacket ->
                            readBlockDataUke(socket)
                                .toObservable()
                                .mergeWith(
                                    Single.fromCallable {
                                        preferences.getInt(
                                            mContext.getString(R.string.pref_blockdatacap),
                                            100
                                        )!!
                                    }.onErrorReturnItem(100)
                                        .flatMapObservable { v ->
                                            writeBlockDataUke(
                                                datastore.getTopRandomMessages(
                                                    v,
                                                    declareHashesPacket
                                                ).toFlowable(BackpressureStrategy.BUFFER),
                                                socket
                                            ).toObservable<HandshakeResult?>()
                                        }
                                )
                                .reduce(stats) { obj, stats -> obj.from(stats) }
                        }
                }
        }.doOnSuccess { LOG.v("bootstrapUkeSocket complete") }
    }

    override fun bootstrapUke(
        band: Int,
        remoteLuid: UUID,
        selfLuid: UUID,
    ): Single<WifiDirectBootstrapRequest> {
        return createGroupCache.updateAndGet { v ->
            when (v) {
                null -> {
                    val createGroupObs = BehaviorSubject.create<WifiDirectBootstrapRequest>()
                    val obs = createGroup(band, remoteLuid, selfLuid)
                        .doOnError { err ->
                            LOG.e("failed to get server socket: $err")
                            firebaseWrapper.recordException(err)
                        }
                        .doFinally {
                            LOG.w("uke completed")
                            createGroupCache.set(null)
                        }
                    obs.toObservable().subscribe(createGroupObs)
                    createGroupObs
                }

                else -> {
                    LOG.e("got cached request cached")
                    v
                }

            }
        }.firstOrError()
            .timeout(68, TimeUnit.SECONDS)
    }

    private fun bootstrapSemeSocket(socket: Socket): Single<HandshakeResult> {
        return Single.defer {
            routingMetadataSeme(
                socket,
                Flowable.just(
                    RoutingMetadataPacket.newBuilder().setEmpty().build()
                )
            )
                .ignoreElements()
                .andThen(
                    Single.fromCallable {
                        preferences.getInt(
                            mContext.getString(R.string.pref_identitycap),
                            200
                        )!!
                    }.onErrorReturnItem(200)
                        .flatMapObservable { v ->
                            identityPacketSeme(
                                socket,
                                datastore.getTopRandomIdentities(
                                    v
                                )
                            )
                        }
                )
                .reduce(ArrayList()) { list: ArrayList<IdentityPacket>, packet: IdentityPacket ->
                    list.add(packet)
                    list
                }
                .flatMap { p ->
                    LOG.v("inserting identity packet seme")
                    datastore.insertIdentityPacket(p).toSingleDefault(
                        HandshakeResult(
                            p.size,
                            0,
                            HandshakeResult.TransactionStatus.STATUS_SUCCESS
                        )
                    )
                }
                .flatMap { stats ->
                    declareHashesSeme(socket)
                        .doOnSuccess { LOG.v("received declare hashes packet seme") }
                        .flatMapObservable { declareHashesPacket ->
                            readBlockDataSeme(socket)
                                .toObservable()
                                .mergeWith(
                                    writeBlockDataSeme(
                                        socket,
                                        datastore.getTopRandomMessages(
                                            32,
                                            declareHashesPacket
                                        )
                                            .toFlowable(BackpressureStrategy.BUFFER)
                                    ).toObservable()
                                )
                        }
                        .reduce(stats) { obj, st -> obj.from(st) }
                }
        }
    }

    override fun bootstrapSeme(
        name: String,
        passphrase: String,
        band: Int,
        ownerPort: Int,
        self: UUID
    ) {
        val mutex = getMutex("semeConnect")
        val d = mutex.await()
            .doOnSubscribe { LOG.w("wifi direct connection: $name $passphrase $ownerPort waiting on lock") }
            .flatMapPublisher { m ->
                LOG.w("wifi direct connection: $name $passphrase $ownerPort ACQUIRED lock")
                Completable.defer {
                    advertiser.clear(false)
                    advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid())
                }.andThen(connectToGroup(name, passphrase, 60, band))
                    .flatMapPublisher { info ->
                        serverSocketManager.getServerSocket().flatMapPublisher { socket ->
                            LOG.v("seme listening for inter-seme connections")
                            retryDelay(
                                socketProvider.getSocket(
                                    info.groupOwnerAddress()!!,
                                    ownerPort,
                                    advertiser.getHashLuid()
                                ), 31, 1
                            )
                                .flatMapPublisher { ownerSocket ->
                                    LOG.v("seme got send ip socket: ${ownerSocket.remoteSocketAddress}, ${ownerSocket.port}")
                                    sendSelfIp(ownerSocket, self, ownerPort)
                                        .doOnError { err -> LOG.w("seme sendSelfIp failed $err") }
                                        .timeout(59, TimeUnit.SECONDS, timeoutScheduler)
                                        .flatMapPublisher { packet ->
                                            val size = packet.addresses.size.toLong()
                                            LOG.v("seme got ip announce from uke, connected size: $size")
                                            bootstrapSemeSocket(ownerSocket)
                                                .doOnError { err -> LOG.w("seme bootstrapSemeSocket failed $err") }
                                                .doOnSuccess { LOG.v("bootstrapSeme client success") }
                                                .toFlowable()
                                                .mergeWith(
                                                    Flowable.defer {
                                                        if (size > 0) {
                                                            socket.accept()
                                                                .repeat(size)
                                                                .timeout(
                                                                    60,
                                                                    TimeUnit.SECONDS,
                                                                    timeoutScheduler
                                                                ) //TODO: remove hardcoded time
                                                                .onErrorResumeNext(Flowable.empty())
                                                                .flatMapSingle { s ->
                                                                    bootstrapUkeSocket(s.socket)
                                                                        .doOnError { err -> LOG.w("seme bootstrapUkeSocket failed $err") }
                                                                }
                                                                .mergeWith(
                                                                    Flowable.fromIterable(packet.addresses.values)
                                                                        .flatMapSingle { peerAddr ->
                                                                            LOG.w("bootstrapping proxy peer ${peerAddr.address.address} ${peerAddr.address.port}")
                                                                            retryDelay(
                                                                                socketProvider.getSocket(
                                                                                    peerAddr.address.address,
                                                                                    peerAddr.address.port,
                                                                                    advertiser.getHashLuid()
                                                                                ), 20, 1
                                                                            )
                                                                                .flatMap { sock ->
                                                                                    bootstrapSemeSocket(
                                                                                        sock
                                                                                    ).doOnError { err ->
                                                                                        LOG.w(
                                                                                            "seme proxy failed $err"
                                                                                        )
                                                                                    }
                                                                                        .timeout(
                                                                                            60,
                                                                                            TimeUnit.SECONDS,
                                                                                            timeoutScheduler
                                                                                        )
                                                                                }
                                                                        })
                                                                .takeWhile {
                                                                    mBroadcastReceiver.connectedDevices()
                                                                        .isNotEmpty()
                                                                }
                                                        } else {
                                                            Flowable.empty()
                                                        }
                                                    }
                                                )
                                        }

                                }
                        }
                    }
                    .concatWith(
                        removeGroup().onErrorComplete()
                    )
                    .onErrorResumeNext { err: Throwable ->
                        removeGroup()
                            .onErrorComplete()
                            .andThen(Flowable.error(err))
                    }
                    .timeout(128, TimeUnit.SECONDS)
                    .doOnError { err ->
                        LOG.w("seme error: $err")
                        err.printStackTrace()
                    }
                    .doFinally {
                        LOG.w("wifi direct client/seme complete")
                        //    ukes.clear()
                        m.release()
                        scheduler.get().releaseWakeLock()
                    }
            }.onErrorResumeNext { err: Throwable ->
                leState.get().refreshPeers().andThen(Flowable.error(err))
            }
            .subscribe(
                { v -> LOG.v("wifi seme client next ${v.success}") },
                { err ->
                    LOG.w("wifi seme client error $err")
                    connects.forEach { (v, _) ->
                        v.dispose()
                    }
                }
            )
        connects[d] = true
    }

    /*
     * sometimes group creation, connection, or other wifi p2p operations fail for reasons only known
     * to google engineers. We can use these helper functions to retry these operations
     */

    //transfer blockdata packets as SEME
    private fun writeBlockDataSeme(
        socket: Socket,
        stream: Flowable<BlockDataStream>
    ): Completable {
        return stream.concatMapCompletable { blockDataStream ->
            blockDataStream.headerPacket.writeToStream(
                socket.getOutputStream(),
                writeScheduler
            )
                .doOnComplete { LOG.v("wrote headerpacket to client socket") }
                .andThen(
                    blockDataStream.sequencePackets
                        .doOnNext { packet -> LOG.v("seme writing sequence packet: " + packet!!.data.size) }
                        .concatMapCompletable { sequencePacket ->
                            sequencePacket.writeToStream(
                                socket.getOutputStream(),
                                writeScheduler
                            )
                        }
                        .doOnComplete { LOG.v("wrote sequence packets to client socket") }
                )
                .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
        }
    }

    //transfer blockdata packets as UKE
    private fun writeBlockDataUke(
        stream: Flowable<BlockDataStream>,
        socket: Socket
    ): Completable {
        return stream.doOnSubscribe { LOG.v("subscribed to BlockDataStream observable") }
            .doOnNext { LOG.v("writeBlockData processing BlockDataStream") }
            .concatMapCompletable { blockDataStream ->
                blockDataStream.headerPacket.writeToStream(
                    socket.getOutputStream(),
                    writeScheduler
                )
                    .doOnComplete { LOG.v("server wrote header packet") }
                    .andThen(
                        blockDataStream.sequencePackets
                            .doOnNext { packet -> LOG.v("uke writing sequence packet: " + packet.data.size) }
                            .concatMapCompletable { blockSequencePacket ->
                                blockSequencePacket.writeToStream(
                                    socket.getOutputStream(),
                                    writeScheduler
                                )
                            }
                            .doOnComplete { LOG.v("server wrote sequence packets") }
                    )
                    .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
            }.doOnComplete { LOG.v("writeBlockDataUke complete") }
    }

    /*
     * read blockdata packets as UKE and stream into datastore. Even if a transfer is interrupted we should still have
     * the files/metadata from packets we received
     */
    private fun readBlockDataUke(socket: Socket): Single<HandshakeResult> {
        return ScatterSerializable.parseWrapperFromCRC(
            BlockHeaderPacket.parser(),
            socket.getInputStream(),
            readScheduler
        )
            .doOnSuccess { header -> LOG.v("uke reading header ${header.userFilename}") }
            .flatMap { headerPacket ->
                LOG.v("uke read header success")
                if (headerPacket.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = BlockDataStream(
                        headerPacket,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacket.parser(),
                            socket.getInputStream(),
                            readScheduler,
                        )
                            .repeat()
                            .takeWhile { p -> !p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("uke reading sequence packet: " + packet.data.size)
                            }
                            .doOnComplete { LOG.v("server read sequence packets") },
                        datastore.cacheDir
                    )
                    datastore.insertMessage(m).toSingleDefault(1)
                }
            }
            .repeat()
            .takeWhile { n -> n > 0 }
            .reduce { a, b -> a + b }
            .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
            .toSingle(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
            .doOnError { e -> LOG.e("uke: error when reading message: $e") }
    }

    /*
     * read blockdata packets as SEME and stream into datastore. Even if a transfer is interrupted we should still have
     * the files/metadata from packets we received
     */
    private fun readBlockDataSeme(
        socket: Socket
    ): Single<HandshakeResult> {
        return ScatterSerializable.parseWrapperFromCRC(
            BlockHeaderPacket.parser(),
            socket.getInputStream(),
            readScheduler
        )
            .doOnSuccess { header -> LOG.v("seme reading header ${header.userFilename}") }
            .flatMap { header ->
                if (header.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = BlockDataStream(
                        header,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacket.parser(),
                            socket.getInputStream(),
                            readScheduler
                        )
                            .repeat()
                            .takeWhile { p -> !p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("seme reading sequence packet: ${packet.data.size} ${packet.isEnd}")
                            }
                            .doOnComplete { LOG.v("seme complete read sequence packets") },
                        datastore.cacheDir
                    )
                    datastore.insertMessage(m).subscribeOn(timeoutScheduler)
                        .toSingleDefault(1)
                }
            }
            .repeat()
            .doOnNext { v -> LOG.v("seme read header packet $v") }
            .takeWhile { n -> n > 0 }
            .reduce { a, b -> a + b }
            .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
            .toSingle(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
            .doOnError { e -> LOG.e("seme: error when reading message: $e") }
            .doOnSuccess { LOG.v("seme read blockdata complete") }
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