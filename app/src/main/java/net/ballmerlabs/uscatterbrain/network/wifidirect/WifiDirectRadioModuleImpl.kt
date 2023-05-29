package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.*
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.MaybeSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

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
@ScatterbrainTransactionScope
class WifiDirectRadioModuleImpl @Inject constructor(
    private val mManager: WifiP2pManager,
    private val mContext: Context,
    private val datastore: ScatterbrainDatastore,
    private val preferences: RouterPreferences,
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler,
    private val channel: WifiP2pManager.Channel,
    private val mBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val firebaseWrapper: FirebaseWrapper = MockFirebaseWrapper(),
    private val infoComponentProvider: Provider<WifiDirectInfoSubcomponent.Builder>,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val serverSocketManager: ServerSocketManager,
    private val socketProvider: SocketProvider,
    private val manager: WifiManager,
    private val advertiser: Advertiser
) : WifiDirectRadioModule {
    private val LOG by scatterLog()

    private val connectedPeers = ConcurrentHashMap<InetSocketAddress, InetSocketAddress>()
    private val connectedAddressSet = ConcurrentHashMap<InetSocketAddress, UUID>()
    private val createGroupCache = AtomicReference<Flowable<HandshakeResult>?>()
    private val bootstrapRequest = BehaviorSubject.create<WifiDirectBootstrapRequest>()

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

    private fun createGroupSingle(): Single<WifiDirectInfo> {
        return Single.defer {
            val subject = CompletableSubject.create()
            try {
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        LOG.v("successfully created group!")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        LOG.w("failed to create group: ${reasonCodeToString(reason)}")
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
                        mManager.createGroup(channel, listener)
                    })
                    .doOnError { err -> LOG.e("createGroup error: $err") }
                    .takeUntil { wifiP2pInfo ->
                        wifiP2pInfo.groupFormed() && wifiP2pInfo.isGroupOwner() && wifiP2pInfo.groupOwnerAddress() != null
                    }
                    .doOnComplete { LOG.v("createGroup return success") }
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
                mManager.requestGroupInfo(channel, listener)
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
                mManager.requestConnectionInfo(channel, listener)
            } catch (exc: SecurityException) {
                firebaseWrapper.recordException(exc)
                subject.onError(exc)
            }
            subject
        }.doOnSuccess { LOG.v("got connectionInfo") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
            .doOnComplete { LOG.e("empty connectionInfo") }

    }

    private fun createGroupDryRun(): Completable {
        return requestGroupInfo()
            .switchIfEmpty(
                createGroupSingle()
                    .ignoreElement()
                    .toMaybe()
            ).ignoreElement()
    }


    private fun sendConnectedIps(sock: Socket): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder()
            LOG.e("sendConnectedIps ${connectedPeers.size} ${sock.localAddress}")
            //    builder.addAddress(advertiser.getHashLuid(), InetSocketAddress(sock.localAddress, sock.localPort))
            connectedPeers
                .filter { v -> v.key.address == sock.localAddress }
                .forEach { v -> builder.addAddress(connectedAddressSet[v.value]!!, v.value) }
            builder.build()
                .writeToStream(sock.getOutputStream(), operationsScheduler)
                .andThen(
                    ScatterSerializable.parseWrapperFromCRC(
                        IpAnnouncePacket.parser(),
                        sock.getInputStream(),
                        operationsScheduler
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


    private fun sendSelfIp(socket: Socket, port: Int): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder()
                .addAddress(advertiser.getHashLuid(), InetSocketAddress(socket.localAddress, port))
                .build()

            ScatterSerializable.parseWrapperFromCRC(
                IpAnnouncePacket.parser(),
                socket.getInputStream(),
                operationsScheduler
            )
                .toObservable()
                .mergeWith(
                    builder.writeToStream(socket.getOutputStream(), operationsScheduler)
                )
                .firstOrError()

        }
    }

    private fun initiateConnectionAndAccept(
        name: String,
        passphrase: String,
        band: Int,
        ownerPort: Int,
    ): Flowable<HandshakeResult> {
        return connectToGroup(name, passphrase, 60, band)
            .subscribeOn(operationsScheduler)
            .flatMapPublisher { info ->
                serverSocketManager.getServerSocket().flatMapPublisher { socket ->
                    LOG.v("seme listening for inter-seme connections")
                    retryDelay(
                        socketProvider.getSocket(
                            info.groupOwnerAddress()!!,
                            ownerPort,
                            advertiser.getHashLuid()
                        ), 10, 1
                    )
                        .flatMapPublisher { ownerSocket ->
                            LOG.v("seme got send ip socket: ${ownerSocket.remoteSocketAddress}, ${ownerSocket.port}")
                            sendSelfIp(ownerSocket, ownerPort)
                                .flatMapPublisher { packet ->
                                    val size = packet.addresses.size.toLong()
                                    LOG.v("seme got ip announce from uke, connected size: $size")
                                    bootstrapSemeSocket(ownerSocket)
                                        .toFlowable()
                                        .concatWith(
                                            socket.accept()
                                                .repeat()
                                                .repeat(size)
                                                //  .takeWhile { mBroadcastReceiver.connectedDevices().isNotEmpty() }
                                                .flatMapSingle { s -> bootstrapUkeSocket(s.socket) }
                                                .mergeWith(
                                                    Flowable.fromIterable(packet.addresses.values)
                                                        .flatMapSingle { peerAddr ->
                                                            retryDelay(
                                                                socketProvider.getSocket(
                                                                    peerAddr.address.address,
                                                                    peerAddr.address.port,
                                                                    advertiser.getHashLuid()
                                                                ), 10, 5
                                                            )
                                                                .flatMap { sock ->
                                                                    bootstrapSemeSocket(
                                                                        sock
                                                                    )
                                                                }
                                                        })
                                        )
                                }

                        }
                }
            }.concatWith(removeGroup())
    }

    /**
     * create a wifi direct group with this device as the owner
     */
    override fun createGroup(
        band: Int,
        bootstrap: (WifiDirectBootstrapRequest) -> Completable
    ): Flowable<DisposableSocket> {
        return removeGroup().andThen(createGroupSingle().ignoreElement()
            .andThen(mBroadcastReceiver.observeConnectionInfo()
                .takeUntil { i -> i.isGroupOwner() && i.groupFormed() }
                .ignoreElements())
            .andThen(retryDelay(requestGroupInfo().toSingle(), 10, 1)))

            .flatMapPublisher { groupInfo ->
                LOG.e("created wifi direct group ${groupInfo.networkName} ${groupInfo.passphrase}")
                serverSocketManager.getServerSocket().flatMapPublisher { serverSocket ->
                    LOG.v("got socket ${serverSocket.socket.localPort}")
                    val request = bootstrapRequestProvider.get()
                        .wifiDirectArgs(
                            BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                                passphrase = groupInfo.passphrase,
                                name = groupInfo.networkName,
                                role = ConnectionRole.ROLE_UKE,
                                band = band,
                                port = serverSocket.socket.localPort
                            )
                        ).build()!!.wifiBootstrapRequest()
                    bootstrapRequest.onNext(request)
                    serverSocket.accept()
                        .repeat()
                        .mergeWith(mBroadcastReceiver.observePeers().flatMapCompletable { v ->
                            LOG.v("createGroup sees peerlist at ${v.deviceList.size}")
                            if (v.deviceList.isEmpty() && connectedAddressSet.isEmpty()) {
                                removeGroup()
                                    .doOnComplete { serverSocket.socket.close() }
                            } else {
                                Completable.complete()
                            }
                        })
                        .mergeWith(bootstrap(request).subscribeOn(operationsScheduler))
                        .takeWhile { mBroadcastReceiver.connectedDevices().isNotEmpty() }
                        .doOnError { err -> LOG.w("uke socket error $err, probably just a disconnect") }
                        .onErrorResumeNext(Flowable.empty())
                        .flatMapSingle { sock ->
                            sendConnectedIps(sock.socket).ignoreElement()
                                .toSingleDefault(sock)
                        }
                        .doFinally {
                            LOG.v("uke server complete")
                            connectedPeers.clear()
                        }
                }
            }
            .doOnComplete { LOG.e("createGroup completed") }
            .subscribeOn(operationsScheduler)

    }

    override fun wifiDirectIsUsable(): Single<Boolean> {
        return Single.just(true)
        return createGroupDryRun()
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
            val subject = CompletableSubject.create()
            val actionListener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    subject.onComplete()
                }

                override fun onFailure(p0: Int) {
                    LOG.e("failed to remove group: ${reasonCodeToString(p0)}")
                    subject.onError(IllegalStateException("failed ${reasonCodeToString(p0)}"))
                }

            }

            subject.mergeWith(Completable.fromAction {
                mManager.removeGroup(channel, actionListener)
            })
                .andThen(mBroadcastReceiver.observeConnectionInfo()
                    .doOnError { err -> LOG.e("removeGroup error: $err") }
                    .takeUntil { wifiP2pInfo -> !wifiP2pInfo.groupFormed() and !wifiP2pInfo.isGroupOwner() }
                    .ignoreElements()
                    .doOnComplete { LOG.v("removeGroup return success") }
                )

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

    override fun connectToGroup(
        name: String,
        passphrase: String,
        timeout: Int,
        band: Int
    ): Single<WifiDirectInfo> {
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
        return requestConnectionInfo().flatMap { info ->
            if (info.isGroupOwner) {
                removeGroup().andThen(connection.toMaybe())
            } else {
                connection.toMaybe()
            }
        }.switchIfEmpty(connection)
    }

    override fun getBand(): Int {
        return FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        return if (manager.is5GHzBandSupported)
            FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ
        else
            FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
    }

    private fun cancelConnection(): Completable {
        val cancel = Completable.defer {
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
                    mManager.cancelConnect(channel, connectListener)
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
            LOG.e("initiateConnection ${config.networkName} ${config.passphrase}")
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
                    mManager.connect(channel, config, connectListener)
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
        return retryDelay(connection, 10, 5)
    }

    private fun ackBarrier(socket: Socket, success: Boolean = true): Completable {
        return AckPacket.newBuilder(success)
            .build()
            .writeToStream(socket.getOutputStream(), operationsScheduler)
            .mergeWith(
                ScatterSerializable.parseWrapperFromCRC(
                    AckPacket.parser(),
                    socket.getInputStream(),
                    operationsScheduler
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
                    operationsScheduler
                )
                    .toObservable()
                    .mergeWith(
                        declareHashesPacket.writeToStream(
                            socket.getOutputStream(),
                            operationsScheduler
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
            .flatMap { sock: Socket ->
                ScatterSerializable.parseWrapperFromCRC(
                    RoutingMetadataPacket.parser(),
                    sock.getInputStream(),
                    operationsScheduler
                )
                    .toObservable()
                    .repeat()
                    .takeWhile { routingMetadataPacket ->
                        val end = !routingMetadataPacket.isEmpty
                        if (!end) {
                            LOG.v("routingMetadata seme end of stream")
                        }
                        end
                    } //TODO: timeout here
                    .mergeWith(packets.concatMapCompletable { p ->
                        p.writeToStream(sock.getOutputStream(), operationsScheduler)
                    })
            }
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
            .flatMapObservable { sock: Socket ->
                ScatterSerializable.parseWrapperFromCRC(
                    IdentityPacket.parser(),
                    sock.getInputStream(),
                    operationsScheduler
                )
                    .toObservable()
                    .repeat()
                    .takeWhile { identityPacket ->
                        val end = !identityPacket.isEnd
                        if (!end) {
                            LOG.v("identitypacket seme end of stream")
                        }
                        end
                    }
                    .mergeWith(packets.concatMapCompletable { p ->
                        p.writeToStream(sock.getOutputStream(), operationsScheduler)
                            .doOnComplete { LOG.v("wrote single identity packet") }
                    })
            }.doOnComplete { LOG.v("identity packets complete") }
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
            .timeout(timeout.toLong(), TimeUnit.SECONDS, operationsScheduler)
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
                                    writeBlockDataUke(
                                        datastore.getTopRandomMessages(
                                            preferences.getInt(
                                                mContext.getString(R.string.pref_blockdatacap),
                                                100
                                            )!!,
                                            declareHashesPacket
                                        ).toFlowable(BackpressureStrategy.BUFFER),
                                        socket
                                    ).toObservable()
                                )
                                .reduce(stats) { obj, stats -> obj.from(stats) }
                        }
                        .flatMap { v -> ackBarrier(socket).toSingleDefault(v) }
                }
        }
    }

    override fun bootstrapUke(
        band: Int,
        bootstrap: (WifiDirectBootstrapRequest) -> Completable
    ): Flowable<HandshakeResult> {
        return createGroupCache.updateAndGet { v ->
            when (v) {
                null -> {
                    createGroup(band, bootstrap)
                        .doOnError { err ->
                            LOG.e("failed to get server socket: $err")
                            firebaseWrapper.recordException(err)
                        }
                        .flatMapSingle { socket ->
                            LOG.e("uke bootstrapping")
                            bootstrapUkeSocket(socket.socket)
                        }
                        .doFinally { createGroupCache.set(null) }
                }

                else -> v.mergeWith(bootstrapRequest.flatMapCompletable { request ->
                    bootstrap(
                        request
                    ).subscribeOn(operationsScheduler)
                })

            }
        }!!

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
                    identityPacketSeme(
                        socket,
                        datastore.getTopRandomIdentities(
                            preferences.getInt(
                                mContext.getString(R.string.pref_identitycap),
                                200
                            )!!
                        )
                    )
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
                .flatMap { v -> ackBarrier(socket).toSingleDefault(v) }
        }
    }

    override fun bootstrapSeme(
        name: String,
        passphrase: String,
        band: Int,
        port: Int
    ): Flowable<HandshakeResult> {
        return initiateConnectionAndAccept(
            name,
            passphrase,
            band,
            port
        )
    }

    /**
     * begin data transfer using a bootstrap request from another transport module
     *
     * NOTE: the protocol behavior for this module is defined here
     *
     * @param upgradeRequest BootstrapRequest containing group name and PSK
     * @return single returning HandshakeResult with transaction stats
     */
    override fun bootstrapFromUpgrade(
        upgradeRequest: BootstrapRequest,
        luid: UUID,
        bootstrap: (WifiDirectBootstrapRequest) -> Completable
    ): Flowable<HandshakeResult> {
        val s = Flowable.defer {
            LOG.v(
                "bootstrapFromUpgrade: " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                        + " " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE) + " "
                        + upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
            )
            when {
                upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                        == ConnectionRole.ROLE_UKE -> {
                    bootstrapUke(
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_BAND).toInt(),
                        bootstrap
                    )
                }

                upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                        == ConnectionRole.ROLE_SEME -> {
                    val name = upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                    val passphrase =
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE)
                    val band =
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_BAND).toInt()
                    val port =
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PORT).toInt()
                    bootstrapSeme(name, passphrase, band, port)
                }

                else -> {
                    Flowable.error(IllegalStateException("invalid role"))
                }
            }
        }

        return s
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
                operationsScheduler
            )
                .doOnComplete { LOG.v("wrote headerpacket to client socket") }
                .andThen(
                    blockDataStream.sequencePackets
                        .doOnNext { packet -> LOG.v("seme writing sequence packet: " + packet!!.data.size) }
                        .concatMapCompletable { sequencePacket ->
                            sequencePacket.writeToStream(
                                socket.getOutputStream(),
                                operationsScheduler
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
                    operationsScheduler
                )
                    .doOnComplete { LOG.v("server wrote header packet") }
                    .andThen(
                        blockDataStream.sequencePackets
                            .doOnNext { packet -> LOG.v("uke writing sequence packet: " + packet.data.size) }
                            .concatMapCompletable { blockSequencePacket ->
                                blockSequencePacket.writeToStream(
                                    socket.getOutputStream(),
                                    operationsScheduler
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
            operationsScheduler
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
                            operationsScheduler,
                        )
                            .repeat()
                            .takeUntil { p -> p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("uke reading sequence packet: " + packet.data.size)
                            }
                            .doOnComplete { LOG.v("server read sequence packets") },
                        datastore.cacheDir
                    )
                    datastore.insertMessage(m).andThen(m.await()).toSingleDefault(1)
                }
            }
            .repeat()
            .takeWhile { n -> n > 0 }
            .reduce { a, b -> a + b }
            .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
            .toSingle(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
            .doOnError { e -> LOG.e("uke: error when reading message: $e") }
            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))

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
            operationsScheduler
        )
            .doOnSuccess { header -> LOG.v("seme reading header ${header.userFilename}") }
            .flatMap { header ->
                LOG.v("seme read header success")
                if (header.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = BlockDataStream(
                        header,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacket.parser(),
                            socket.getInputStream(),
                            operationsScheduler
                        )
                            .repeat()
                            .takeUntil { p -> p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("seme reading sequence packet: " + packet.data.size)
                            }
                            .doOnComplete { LOG.v("seme complete read sequence packets") },
                        datastore.cacheDir
                    )
                    datastore.insertMessage(m).andThen(m.await()).subscribeOn(operationsScheduler)
                        .toSingleDefault(1)
                }
            }
            .repeat()
            .takeWhile { n -> n > 0 }
            .reduce { a, b -> a + b }
            .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
            .toSingle(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
            .doOnError { e -> LOG.e("seme: error when reading message: $e") }
            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
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