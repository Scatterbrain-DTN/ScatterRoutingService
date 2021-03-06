package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.internal.HandshakeResult
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.getGlobalHash
import net.ballmerlabs.uscatterbrain.db.getGlobalHashProto
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.InterceptableServerSocket.InterceptableServerSocketFactory
import net.ballmerlabs.uscatterbrain.network.wifidirect.InterceptableServerSocket.SocketConnection
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.Companion.TAG
import java.net.InetAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
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
        private val channel: WifiP2pManager.Channel,
        private val mBroadcastReceiver: WifiDirectBroadcastReceiver
) : WifiDirectRadioModule {

    /*
     * we need to unregister and register the receiver when
     * the service stops and starts. NOTE: since we use a foreground
     * service we do not unregister it when the app's activity is minimized
     */
    override fun unregisterReceiver() {
        Log.v(TAG, "unregistering broadcast receier")
        try {
            mContext.unregisterReceiver(mBroadcastReceiver.asReceiver())
        } catch (illegalArgumentException: IllegalArgumentException) {
            Log.w(TAG, "attempted to unregister nonexistent receiver, ignore.")
        }
    }

    override fun registerReceiver() {
        Log.v(TAG, "registering broadcast receiver")
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

    /**
     * create a wifi direct group with this device as the owner
     */
    override fun createGroup(): Single<WifiDirectBootstrapRequest> {
        return Single.defer {
            val groupRetry = AtomicReference(5)
            val subject = SingleSubject.create<WifiDirectBootstrapRequest>()


            val groupListener = WifiP2pManager.GroupInfoListener { groupInfo ->
                if (groupInfo != null && groupInfo.isGroupOwner) {
                    val bootstrap = WifiDirectBootstrapRequest.create(
                            groupInfo.passphrase,
                            groupInfo.networkName,
                            ConnectionRole.ROLE_UKE
                    )
                    subject.onSuccess(bootstrap)
                } else if (groupInfo != null) {
                    subject.onError(IllegalStateException("group created but not owner"))
                }
            }

            val listener: WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.v(TAG, "successfully created group!")
                    groupOperationInProgress.set(false)
                    if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        subject.onError(SecurityException("cannot create group without fine location permisison"))
                        return
                    }
                    mManager.requestGroupInfo(channel, groupListener)
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "failed to create group: " + reasonCodeToString(reason))
                    if (groupRetry.getAndSet(groupRetry.get()+1) > 0 && ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        mManager.createGroup(channel, this)
                    }
                }
            }

            mManager.createGroup(channel, listener)
            mBroadcastReceiver.observeConnectionInfo()
                    .doOnSubscribe { Log.e(TAG, "subscribed") }
                    .doOnError { err -> Log.e(TAG, "createGroup error: $err")}
                    .doOnNext { t ->
                        Log.e(TAG, "received a WifiP2pInfo: $t")
                        if (t.groupFormed && t.isGroupOwner) {
                            mManager.requestGroupInfo(channel, groupListener)
                        }
                    }
                    .takeUntil { wifiP2pInfo-> (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) }
                    .ignoreElements()
                    .doOnComplete { Log.v(TAG, "createGroup return success") }
                    .doFinally { groupOperationInProgress.set(false) }
                    .doOnComplete {
                    }
                    .andThen(subject)
        }
    }
    
    override fun removeGroup(): Completable {
        val c = Completable.defer {
            val subject = CompletableSubject.create()
            val actionListener = object: WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    subject.onComplete()
                }

                override fun onFailure(p0: Int) {
                    subject.onError(IllegalStateException("failed ${reasonCodeToString(p0)}"))
                }

            }
            mManager.removeGroup(channel, actionListener)
            subject
        }

        return retryDelay(c, 10, 5)
    }

    private fun getTcpSocket(address: InetAddress): Single<Socket> {
        return Single.fromCallable { Socket(address, SCATTERBRAIN_PORT) }
                .subscribeOn(operationsScheduler)
    }

    /**
     * connect to a wifi direct group
     * @param name group name. MUST start with DIRECT-*
     * @param passphrase group PSK. minimum 8 characters
     */
    override fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo> {
       return Single.defer {
           val subject = SingleSubject.create<WifiP2pInfo>()

           val infoListener = WifiP2pManager.ConnectionInfoListener { info ->
               subject.onSuccess(info)
           }

           val groupListener = WifiP2pManager.GroupInfoListener { group ->
               if (group != null && !group.isGroupOwner && group.passphrase.equals(passphrase) &&
                       group.networkName.equals(name)) {
                   mManager.requestConnectionInfo(channel, infoListener)
               } else {
                   if (!groupConnectInProgress.getAndSet(true)) {
                       val fakeConfig = FakeWifiP2pConfig(
                               passphrase = passphrase,
                               networkName = name
                       )

                       retryDelay(initiateConnection(fakeConfig.asConfig()), 20, 1)
                               .andThen(awaitConnection(timeout))
                               .subscribe(subject)
                   } else {
                       awaitConnection(timeout)
                               .subscribe(subject)
                   }
               }
           }
           if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION)
                   != PackageManager.PERMISSION_GRANTED) {
               Single.error(SecurityException("needs fine location"))
           } else {
               mManager.requestGroupInfo(channel, groupListener)
               subject
           }
            
        }
    }

    /*
     * conect using a wifip2pconfig object
     */
    private fun initiateConnection(config: WifiP2pConfig): Completable {
        return Completable.defer {
            Log.e(TAG, " mylooper " + (Looper.myLooper() == Looper.getMainLooper()))
            val subject = CompletableSubject.create()
            val connectRetry = AtomicReference(10)
            try {

                val connectListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.v(TAG, "connected to wifi direct group! FMEEEEE! AM HAPPY!")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "failed to connect to wifi direct group, am v sad. I cry now: " + reasonCodeToString(reason))
                        if (connectRetry.getAndSet(connectRetry.get() - 1) > 0) {
                            mManager.connect(channel, config, this)
                        } else {
                            subject.onError(IllegalStateException("failed to connect to group: " + reasonCodeToString(reason)))
                        }
                    }
                }

                mManager.connect(channel, config, connectListener)
                return@defer subject
            } catch (e: SecurityException) {
                return@defer Completable.error(e)
            }
        }
    }

    //transfer declare hashes packet as UKE
    private fun declareHashesUke(): Single<DeclareHashesPacket> {
        Log.v(TAG, "declareHashesUke")
        return serverSocket
                .flatMap { socket: Socket -> declareHashesSeme(socket) }
    }

    //transfer declare hashes packet as SEME
    private fun declareHashesSeme(socket: Socket): Single<DeclareHashesPacket> {
        Log.v(TAG, "declareHashesSeme")
        return datastore.declareHashesPacket
                .flatMapObservable { declareHashesPacket: DeclareHashesPacket ->
                    ScatterSerializable.parseWrapperFromCRC(
                            DeclareHashesPacket.parser(),
                            socket.getInputStream(),
                            operationsScheduler
                    )
                            .toObservable()
                            .mergeWith(declareHashesPacket.writeToStream(
                                    socket.getOutputStream(),
                                    operationsScheduler
                            ))
                            .subscribeOn(operationsScheduler)
                }
                .firstOrError()
    }

    //transfer routing metadata packet as UKE
    private fun routingMetadataUke(packets: Flowable<RoutingMetadataPacket>): Observable<RoutingMetadataPacket> {
        return serverSocket
                .flatMapObservable { sock: Socket -> routingMetadataSeme(sock, packets) }
    }

    //transfer routing metadata packet as SEME
    private fun routingMetadataSeme(socket: Socket, packets: Flowable<RoutingMetadataPacket>): Observable<RoutingMetadataPacket> {
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
                                    Log.v(TAG, "routingMetadata seme end of stream")
                                }
                                end
                            } //TODO: timeout here
                            .mergeWith(packets.concatMapCompletable { p: RoutingMetadataPacket ->
                                Log.e("debug", "writing routing metadata")
                                p.writeToStream(sock.getOutputStream(), operationsScheduler)
                            })
                            .subscribeOn(operationsScheduler)
                }
    }

    //transfer identity packet as UKE
    private fun identityPacketUke(packets: Flowable<IdentityPacket>): Observable<IdentityPacket> {
        return serverSocket
                .flatMapObservable { sock: Socket -> identityPacketSeme(sock, packets) }
    }

    //transfer identity packet as SEME
    private fun identityPacketSeme(socket: Socket, packets: Flowable<IdentityPacket>): Observable<IdentityPacket> {
        return Single.just(socket)
                .flatMapObservable { sock: Socket ->
                    ScatterSerializable.parseWrapperFromCRC(
                            IdentityPacket.parser(),
                            sock.getInputStream(),
                            operationsScheduler
                            )
                            .toObservable()
                            .repeat()
                            .takeWhile { identityPacket: IdentityPacket ->
                                val end = !identityPacket.isEnd
                                if (!end) {
                                    Log.v(TAG, "identitypacket seme end of stream")
                                }
                                end
                            }
                            .mergeWith(packets.concatMapCompletable { p: IdentityPacket ->
                                p.writeToStream(sock.getOutputStream(), operationsScheduler)
                                        .doOnComplete { Log.v(TAG, "wrote single identity packet") }
                            })
                            .subscribeOn(operationsScheduler)
                }.doOnComplete { Log.v(TAG, "identity packets complete") }
    }

    /*
     * wait for the BroadcastReceiver to say we are connected to a
     * group
     */
    private fun awaitConnection(timeout: Int): Single<WifiP2pInfo> {
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil { info: WifiP2pInfo -> info.groupFormed && !info.isGroupOwner }
                .lastOrError()
                .timeout(timeout.toLong(), TimeUnit.SECONDS, operationsScheduler)
                .doOnSuccess { info: WifiP2pInfo -> Log.v(TAG, "connect to group returned: " + info.groupOwnerAddress) }
                .doOnError { err: Throwable -> Log.e(TAG, "connect to group failed: $err") }
                .doFinally { groupConnectInProgress.set(false) }
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
        Log.v(TAG, "bootstrapFromUpgrade: " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                + " " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE) + " "
                + upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE))
        return when {
            upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                    == ConnectionRole.ROLE_UKE -> {
                routingMetadataUke(Flowable.just(RoutingMetadataPacket.newBuilder().setEmpty().build()))
                        .ignoreElements()
                        .andThen(
                                identityPacketUke(datastore.getTopRandomIdentities(20))
                                        .reduce(ArrayList(), { list: ArrayList<IdentityPacket>, packet: IdentityPacket ->
                                            list.add(packet)
                                            list
                                        }).flatMap { p: ArrayList<IdentityPacket> ->
                                            datastore.insertIdentityPacket(p).toSingleDefault(
                                                    HandshakeResult(
                                                            p.size,
                                                            0,
                                                            HandshakeResult.TransactionStatus.STATUS_SUCCESS
                                                    )
                                            )
                                        }
                        ).flatMap { stats ->
                            declareHashesUke()
                                    .doOnSuccess { Log.v(TAG, "received declare hashes packet uke") }
                                    .flatMap { declareHashesPacket ->
                                        readBlockDataUke()
                                                .toObservable()
                                                .mergeWith(
                                                        writeBlockDataUke(
                                                                datastore.getTopRandomMessages(
                                                                        preferences.getInt(mContext.getString(R.string.pref_blockdatacap), 100),
                                                                        declareHashesPacket
                                                                ).toFlowable(BackpressureStrategy.BUFFER)
                                                        ).toObservable())
                                                .reduce(stats, { obj: HandshakeResult?, stats: HandshakeResult? -> obj!!.from(stats) })
                                    }
                        }
            }
            upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                    == ConnectionRole.ROLE_SEME -> {
                retryDelay(connectToGroup(
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                        upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE),
                        120
                ), 10, 1)
                        .flatMap { info: WifiP2pInfo ->
                            getTcpSocket(info.groupOwnerAddress)
                                    .flatMap { socket ->
                                        routingMetadataSeme(socket, Flowable.just(RoutingMetadataPacket.newBuilder().setEmpty().build()))
                                                .ignoreElements()
                                                .andThen(
                                                        identityPacketSeme(
                                                                socket,
                                                                datastore.getTopRandomIdentities(preferences.getInt(mContext.getString(R.string.pref_identitycap), 200))
                                                        )
                                                )
                                                .reduce(ArrayList(), { list: ArrayList<IdentityPacket>, packet: IdentityPacket ->
                                                    list.add(packet)
                                                    list
                                                })
                                                .flatMap { p ->
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
                                                            .doOnSuccess { Log.v(TAG, "received declare hashes packet seme") }
                                                            .flatMapObservable { declareHashesPacket ->
                                                                readBlockDataSeme(socket)
                                                                        .toObservable()
                                                                        .mergeWith(writeBlockDataSeme(socket, datastore.getTopRandomMessages(32, declareHashesPacket)
                                                                                .toFlowable(BackpressureStrategy.BUFFER)).toObservable())
                                                            }
                                                            .reduce(stats, { obj, st -> obj.from(st) })
                                                }
                                    }
                        }
                        .doOnSubscribe { Log.v(TAG, "subscribed to writeBlockData") }
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


    private fun <T> retryDelay(observable: Observable<T>, count: Int, seconds: Int): Observable<T> {
        return observable
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors: Observable<Throwable> ->
                    errors
                            .zipWith(Observable.range(1, count), { _: Throwable, i: Int -> i })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    private fun retryDelay(completable: Completable, count: Int, seconds: Int): Completable {
        return completable
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors: Flowable<Throwable> ->
                    errors
                            .zipWith(Flowable.range(1, count), { _: Throwable, i: Int -> i })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    private fun <T> retryDelay(single: Single<T>, count: Int, seconds: Int): Single<T> {
        return single
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors ->
                    errors
                            .zipWith(Flowable.range(1, count), { _, i: Int -> i })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    //transfer blockdata packets as SEME
    private fun writeBlockDataSeme(
            socket: Socket,
            stream: Flowable<BlockDataStream>
    ): Completable {
        return stream.concatMapCompletable { blockDataStream ->
            blockDataStream.headerPacket.writeToStream(socket.getOutputStream(), operationsScheduler)
                    .doOnComplete { Log.v(TAG, "wrote headerpacket to client socket") }
                    .andThen(
                            blockDataStream.sequencePackets
                                    .doOnNext { packet -> Log.v(TAG, "seme writing sequence packet: " + packet!!.data.size) }
                                    .concatMapCompletable { sequencePacket ->
                                        sequencePacket.writeToStream(socket.getOutputStream(), operationsScheduler)
                                    }
                                    .doOnComplete { Log.v(TAG, "wrote sequence packets to client socket") }
                    )
                    .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
        }
    }

    //transfer blockdata packets as UKE
    private fun writeBlockDataUke(
            stream: Flowable<BlockDataStream>
    ): Completable {
        return serverSocket
                .flatMapCompletable { socket ->
                    stream.doOnSubscribe { Log.v(TAG, "subscribed to BlockDataStream observable") }
                            .doOnNext { Log.v(TAG, "writeBlockData processing BlockDataStream") }
                            .concatMapCompletable { blockDataStream ->
                                blockDataStream.headerPacket.writeToStream(socket.getOutputStream(), operationsScheduler)
                                        .doOnComplete { Log.v(TAG, "server wrote header packet") }
                                        .andThen(
                                                blockDataStream.sequencePackets
                                                        .doOnNext { packet -> Log.v(TAG, "uke writing sequence packet: " + packet.data.size) }
                                                        .concatMapCompletable { blockSequencePacket ->
                                                            blockSequencePacket.writeToStream(
                                                                    socket.getOutputStream(),
                                                                    operationsScheduler
                                                            )
                                                        }
                                                        .doOnComplete { Log.v(TAG, "server wrote sequence packets") }
                                        )
                                        .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
                            }
                }
    }

    /*
     * read blockdata packets as UKE and stream into datastore. Even if a transfer is interrupted we should still have
     * the files/metadata from packets we received
     */
    private fun readBlockDataUke(): Single<HandshakeResult> {
        return serverSocket
                .flatMap { socket ->
                    ScatterSerializable.parseWrapperFromCRC(
                            BlockHeaderPacket.parser(),
                            socket.getInputStream(),
                            operationsScheduler
                    )
                            .doOnSuccess { header -> Log.v(TAG, "uke reading header ${header.userFilename}") }
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
                                                        Log.v(TAG, "uke reading sequence packet: " + packet.data.size)
                                                    }
                                                    .doOnComplete { Log.v(TAG, "server read sequence packets") }
                                    )
                                    datastore.insertMessage(m).andThen(m.await()).toSingleDefault(1)
                                }
                            }
                            .repeat()
                            .takeWhile { n -> n > 0 }
                            .reduce { a, b -> a + b }
                            .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                            .toSingle(HandshakeResult(0,0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
                            .doOnError { e -> Log.e(TAG, "uke: error when reading message: $e") }
                            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
                }

    }

    private val serverSocket: Single<Socket>
        get() = socketFactory.create(SCATTERBRAIN_PORT)
                .flatMapObservable { obj: InterceptableServerSocket -> obj.observeConnections() }
                .map { conn -> conn.socket }
                .firstOrError()
                .doOnSuccess { Log.v(TAG, "accepted server socket") }

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
                .doOnSuccess { header -> Log.v(TAG, "seme reading header ${header.userFilename}") }
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
                                            Log.v(TAG, "seme reading sequence packet: " + packet.data.size)
                                        }
                                        .doOnComplete { Log.v(TAG, "seme complete read sequence packets") }
                        )
                        datastore.insertMessage(m).andThen(m.await()).subscribeOn(operationsScheduler).toSingleDefault(1)
                    }
                }
                .repeat()
                .takeWhile { n -> n > 0 }
                .reduce { a, b -> a + b }
                .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                .toSingle(HandshakeResult(0,0, HandshakeResult.TransactionStatus.STATUS_SUCCESS))
                .doOnError { e -> Log.e(TAG, "seme: error when reading message: $e") }
                .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
    }

    companion object {
        private const val SCATTERBRAIN_PORT = 7575
        private val socketFactory = InterceptableServerSocketFactory()
        private val groupOperationInProgress = AtomicReference(false)
        private val groupConnectInProgress = AtomicReference(false)
        private val wifidirectDisposable = CompositeDisposable()
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

    init {
        val tcpserverdisposable = socketFactory.create(SCATTERBRAIN_PORT)
                .subscribeOn(operationsScheduler)
                .flatMapObservable { obj: InterceptableServerSocket -> obj.acceptLoop() }
                .doOnComplete { Log.e(TAG, "tcp server completed. fueee") }
                .subscribe(
                        { socket: SocketConnection -> Log.v(TAG, "accepted socket: " + socket.socket) }
                ) { err: Throwable -> Log.e(TAG, "error when accepting socket: $err") }
        wifidirectDisposable.add(tcpserverdisposable)
    }
}