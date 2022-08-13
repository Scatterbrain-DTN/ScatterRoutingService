package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.*
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.MaybeSubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager.Companion.SCATTERBRAIN_PORT
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

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
    private val mManager: WifiP2pManager,
    private val mContext: Context,
    private val datastore: ScatterbrainDatastore,
    private val preferences: RouterPreferences,
    @Named(RoutingServiceComponent.NamedSchedulers.OPERATIONS) private val operationsScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) private val clientScheduler: Scheduler,
    private val channel: WifiP2pManager.Channel,
    private val mBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val firebaseWrapper: FirebaseWrapper = MockFirebaseWrapper(),
    private val infoComponentProvider: Provider<WifiDirectInfoSubcomponent.Builder>,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val serverSocketManager: ServerSocketManager,
    private val socketProvider: SocketProvider
) : WifiDirectRadioModule {
    private val LOG by scatterLog()

    /*
     * we need to unregister and register the receiver when
     * the service stops and starts. NOTE: since we use a foreground
     * service we do not unregister it when the app's activity is minimized
     */
    override fun unregisterReceiver() {
        LOG.v("unregistering broadcast receier")
        try {
            mContext.unregisterReceiver(mBroadcastReceiver.asReceiver())
        } catch (illegalArgumentException: IllegalArgumentException) {
            //firebaseWrapper.recordException(illegalArgumentException)
            LOG.w("attempted to unregister nonexistent receiver, ignore.")
        }
    }

    override fun registerReceiver() {
        LOG.v("registering broadcast receiver")
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

    private fun createGroupSingle(): Completable {
        return Completable.defer {
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
                mBroadcastReceiver.observeConnectionInfo()
                    .doOnSubscribe { mManager.createGroup(channel, listener) }
                    .doOnError { err -> LOG.e("createGroup error: $err") }
                    .takeUntil { wifiP2pInfo -> (wifiP2pInfo.groupFormed() && wifiP2pInfo.isGroupOwner()) }
                    .ignoreElements()
                    .doOnComplete { LOG.v("createGroup return success") }
                    .andThen(subject)
            } catch (exc: SecurityException) {
                Completable.error(exc)
            }
        }

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
            .doOnSuccess { LOG.v("got groupinfo") }
            .doOnComplete { LOG.v("requestGroupInfo completed") }
            .doOnError { err -> firebaseWrapper.recordException(err) }
    }

    /**
     * create a wifi direct group with this device as the owner
     */
    override fun createGroup(): Single<WifiDirectBootstrapRequest> {
        val ret = Single.defer {
            LOG.v("createGroup")

            createGroupSingle()
                .andThen(requestGroupInfo().toSingle())
                .map { groupInfo ->
                    LOG.v("got groupInfo")
                    bootstrapRequestProvider.get()
                        .wifiDirectArgs(
                            BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                                passphrase = groupInfo.passphrase,
                                name = groupInfo.networkName,
                                role = ConnectionRole.ROLE_UKE
                            )
                        ).build()!!.wifiBootstrapRequest()
                }
        }.doOnError { err ->
            LOG.e("$err")
            firebaseWrapper.recordException(err)
        }

        return removeGroup(retries = 9, delay = 1)
            .andThen(retryDelay(ret, 5, 1))
    }

    override fun wifiDirectIsUsable(): Single<Boolean> {
        return createGroup()
            .ignoreElement()
            .andThen(removeGroup(retries = 9, delay = 1))
            .doOnError { err ->
                LOG.e("cry $err")
                err.printStackTrace()
            }
            .timeout(5, TimeUnit.SECONDS)
            .toSingleDefault(true)
            .onErrorReturnItem(false)
            .flatMap { v ->
                if (v) {
                    removeGroup(retries = 9, delay = 1).toSingleDefault(v)
                } else {
                    Single.just(v)
                }
            }
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

            subject.doOnSubscribe {
                mManager.removeGroup(channel, actionListener)
            }
                .andThen(mBroadcastReceiver.observeConnectionInfo()
                    .doOnError { err -> LOG.e("removeGroup error: $err") }
                    .takeUntil { wifiP2pInfo -> !wifiP2pInfo.groupFormed() }
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
        timeout: Int
    ): Single<WifiDirectInfo> {
        return Single.defer {
            val subject = SingleSubject.create<WifiDirectInfo>()

            val infoListener = WifiP2pManager.ConnectionInfoListener { info ->
                subject.onSuccess(wifiDirectInfo(info))
            }

            val groupListener = WifiP2pManager.GroupInfoListener { group ->
                if (group != null && !group.isGroupOwner && group.passphrase.equals(passphrase) &&
                    group.networkName.equals(name)
                ) {
                    mManager.requestConnectionInfo(channel, infoListener)
                } else {
                    val builder = infoComponentProvider.get()
                    val fakeConfig = builder.fakeWifiP2pConfig(
                        WifiDirectInfoSubcomponent.WifiP2pConfigArgs(
                            passphrase = passphrase,
                            networkName = name
                        )
                    ).build()!!.fakeWifiP2pConfig()

                    retryDelay(initiateConnection(fakeConfig.asConfig()), 20, 5)
                        .andThen(awaitConnection(timeout))
                        .subscribe(subject)
                }
            }
            try {
                mManager.requestGroupInfo(channel, groupListener)
            } catch (exc: Exception) {
                LOG.e("failed to requestGroupInfo: $exc")
                firebaseWrapper.recordException(exc)
                exc.printStackTrace()
                subject.onError(exc)
            } catch (exc: SecurityException) {
                LOG.e("needs fine location permission")
                firebaseWrapper.recordException(exc)
                subject.onError(exc)
            }
            subject

        }.doOnError { err -> firebaseWrapper.recordException(err) }
    }

    /*
     * conect using a wifip2pconfig object
     */
    private fun initiateConnection(config: WifiP2pConfig): Completable {
        return Completable.defer {
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
                            )
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
                return@defer Completable.error(e)
            }
        }
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
                            clientScheduler
                        )
                    )
                    .subscribeOn(operationsScheduler)
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
                        p.writeToStream(sock.getOutputStream(), clientScheduler)
                    })
                    .subscribeOn(operationsScheduler)
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
                        p.writeToStream(sock.getOutputStream(), clientScheduler)
                            .doOnComplete { LOG.v("wrote single identity packet") }
                    })
                    .subscribeOn(operationsScheduler)
            }.doOnComplete { LOG.v("identity packets complete") }
    }

    /*
     * wait for the BroadcastReceiver to say we are connected to a
     * group
     */
    private fun awaitConnection(timeout: Int): Single<WifiDirectInfo> {
        return mBroadcastReceiver.observeConnectionInfo()
            .takeUntil { info -> info.groupFormed() && !info.isGroupOwner() }
            .lastOrError()
            .timeout(timeout.toLong(), TimeUnit.SECONDS, operationsScheduler)
            .doOnSuccess { info -> LOG.v("connect to group returned: " + info.groupOwnerAddress()) }
            .doOnError { err -> LOG.e("connect to group failed: $err") }
    }

    /**
     * begin data transfer using a bootstrap request from another transport module
     *
     * NOTE: the protocol behavior for this module is defined here
     *
     * @param upgradeRequest BootstrapRequest containing group name and PSK
     * @return single returning HandshakeResult with transaction stats
     */
    override fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult> {
        LOG.v(
            "bootstrapFromUpgrade: " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                    + " " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE) + " "
                    + upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
        )
        return when {
            upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                    == ConnectionRole.ROLE_UKE -> {
                serverSocketManager.getServerSocket()
                    .subscribeOn(operationsScheduler)
                    .doOnError { err -> LOG.e("failed to get server socket: $err") }
                    .flatMap { socket ->
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
                            }
                    }
            }
            upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                    == ConnectionRole.ROLE_SEME -> {
                retryDelay(
                    connectToGroup(
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE),
                        120
                    ), 10, 5
                )
                    .flatMap { info ->
                        socketProvider.getSocket(info.groupOwnerAddress()!!, SCATTERBRAIN_PORT)
                            .flatMap { socket ->
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
                            }
                    }
                    .flatMap { v -> removeGroup(10, 1).toSingleDefault(v) }
                    .doOnSubscribe { LOG.v("subscribed to writeBlockData") }

            }
            else -> {
                Single.error(IllegalStateException("invalid role"))
            }
        }
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
                clientScheduler
            )
                .doOnComplete { LOG.v("wrote headerpacket to client socket") }
                .andThen(
                    blockDataStream.sequencePackets
                        .doOnNext { packet -> LOG.v("seme writing sequence packet: " + packet!!.data.size) }
                        .concatMapCompletable { sequencePacket ->
                            sequencePacket.writeToStream(
                                socket.getOutputStream(),
                                clientScheduler
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
                    clientScheduler
                )
                    .doOnComplete { LOG.v("server wrote header packet") }
                    .andThen(
                        blockDataStream.sequencePackets
                            .doOnNext { packet -> LOG.v("uke writing sequence packet: " + packet.data.size) }
                            .concatMapCompletable { blockSequencePacket ->
                                blockSequencePacket.writeToStream(
                                    socket.getOutputStream(),
                                    clientScheduler
                                )
                            }
                            .doOnComplete { LOG.v("server wrote sequence packets") }
                    )
                    .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
            }
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
                if (headerPacket.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = BlockDataStream(
                        headerPacket,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacket.parser(),
                            socket.getInputStream(),
                            operationsScheduler
                        )
                            .repeat()
                            .takeUntil { p -> p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("uke reading sequence packet: " + packet.data.size)
                            }
                            .doOnComplete { LOG.v("server read sequence packets") }
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
                            .doOnComplete { LOG.v("seme complete read sequence packets") }
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