package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Predicate
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.InterceptableServerSocket.InterceptableServerSocketFactory
import net.ballmerlabs.uscatterbrain.network.wifidirect.InterceptableServerSocket.SocketConnection
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.Companion.TAG
import org.reactivestreams.Subscription
import java.net.InetAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@RequiresApi(api = Build.VERSION_CODES.Q)
@Singleton
class WifiDirectRadioModuleImpl @Inject constructor(
        private val mManager: WifiP2pManager,
        private val mContext: Context,
        datastore: ScatterbrainDatastore,
        preferences: RouterPreferences,
        @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_READ) readScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_WRITE) writeScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.WIFI_DIRECT_OPERATIONS) operationsScheduler: Scheduler,
        channel: WifiP2pManager.Channel,
        private val mBroadcastReceiver: WifiDirectBroadcastReceiver
) : WifiDirectRadioModule {
    private val mP2pChannel: WifiP2pManager.Channel = channel
    private val readScheduler: Scheduler
    private val writeScheduler: Scheduler
    private val operationsScheduler: Scheduler
    private val datastore: ScatterbrainDatastore
    private val preferences: RouterPreferences
    override fun unregisterReceiver() {
        Log.v(TAG, "unregistering broadcast receier")
        mContext.unregisterReceiver(mBroadcastReceiver.asReceiver())
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

    override fun createGroup(name: String, passphrase: String): Completable {
        return mBroadcastReceiver.observeConnectionInfo()
                            .doOnSubscribe {
                                Log.e(TAG, "subscribed")
                                val config = WifiP2pConfig.Builder()
                                        .setNetworkName(name)
                                        .setPassphrase(passphrase)
                                        .build()
                                val groupRetry = AtomicReference(5)
                                if (!groupOperationInProgress.getAndUpdate { true }) {
                                    val listener: WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener { override fun onSuccess() {
                                        Log.v(TAG, "successfully created group!")
                                        groupOperationInProgress.set(false)
                                    }

                                        override fun onFailure(reason: Int) {
                                            Log.e(TAG, "failed to create group: " + reasonCodeToString(reason))
                                            if (groupRetry.getAndUpdate { v: Int -> v - 1 } > 0 && ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                                mManager.createGroup(mP2pChannel, this)
                                            }
                                        }
                                    }
                                    if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                        mManager.requestGroupInfo(mP2pChannel) { group: WifiP2pGroup? ->
                                            if (group == null) {
                                                Log.v(TAG, "group is null, assuming not created")
                                                mManager.createGroup(mP2pChannel, config, listener)
                                            } else {
                                                mManager.removeGroup(mP2pChannel, object : WifiP2pManager.ActionListener {
                                                    override fun onSuccess() {
                                                        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                                                PackageManager.PERMISSION_GRANTED) {
                                                            mManager.createGroup(mP2pChannel, config, listener)
                                                        }
                                                    }

                                                    override fun onFailure(reason: Int) {
                                                        Log.e(TAG, "failed to remove group")
                                                        groupOperationInProgress.set(false)
                                                    }
                                                })
                                            }
                                        }
                                    }
                                }

                            }
                .doOnError { err -> Log.e(TAG, "createGroup error: $err")}
                            .doOnNext { t: WifiP2pInfo -> Log.e(TAG, "received a WifiP2pInfo: $t") }
                            .takeUntil { wifiP2pInfo: WifiP2pInfo -> (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) }
                            .ignoreElements()
                            .doOnComplete { Log.v(TAG, "createGroup return success") }
                            .doFinally { groupOperationInProgress.set(false) }
    }

    private fun getTcpSocket(address: InetAddress): Single<Socket> {
        return Single.fromCallable { Socket(address, SCATTERBRAIN_PORT) }
                .subscribeOn(operationsScheduler)
    }

    override fun connectToGroup(name: String, passphrase: String, timeout: Int): Single<WifiP2pInfo> {
        return if (!groupConnectInProgress.getAndUpdate { true }) {
            val config = WifiP2pConfig.Builder()
                    .setPassphrase(passphrase)
                    .setNetworkName(name)
                    .build()
            retryDelay(initiateConnection(config), 20, 1)
                    .andThen(awaitConnection(timeout))
        } else {
            awaitConnection(timeout)
        }
    }

    fun discoverPeers(): Completable {
        return Single.fromCallable {
            val subject = CompletableSubject.create()
            val discoverretry = AtomicReference(20)
            try {
                val discoveryListener: WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.v(TAG, "peer discovery request completed, initiating connection")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "peer discovery failed")
                        if (discoverretry.getAndUpdate { v: Int -> v -1 } > 0) {
                            mManager.discoverPeers(mP2pChannel, this)
                        } else {
                            subject.onError(IllegalStateException("failed to discover peers: " + reasonCodeToString(reason)))
                        }
                    }
                }
                mManager.discoverPeers(mP2pChannel, discoveryListener)
            } catch (e: SecurityException) {
                subject.onError(e)
            }
            subject.andThen(awaitPeersChanged(15, TimeUnit.SECONDS))
        }.flatMapCompletable { single: Completable? -> single }
    }

    fun initiateConnection(config: WifiP2pConfig): Completable {
        return Single.fromCallable {
            Log.e(TAG, " mylooper " + (Looper.myLooper() == Looper.getMainLooper()))
            val subject = CompletableSubject.create()
            val connectRetry = AtomicReference(10)
            try {
                val connectListener: WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.v(TAG, "connected to wifi direct group! FMEEEEE! AM HAPPY!")
                        subject.onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "failed to connect to wifi direct group, am v sad. I cry now: " + reasonCodeToString(reason))
                        if (connectRetry.getAndUpdate { v: Int -> v - 1 } > 0) {
                            mManager.connect(mP2pChannel, config, this)
                        } else {
                            subject.onError(IllegalStateException("failed to connect to group: " + reasonCodeToString(reason)))
                        }
                    }
                }
                mManager.connect(mP2pChannel, config, connectListener)
                return@fromCallable subject
            } catch (e: SecurityException) {
                return@fromCallable Completable.error(e)
            }
        }.flatMapCompletable { completable: Completable? -> completable }
    }

    fun declareHashesUke(): Single<DeclareHashesPacket> {
        Log.v(TAG, "declareHashesUke")
        return serverSocket
                .flatMap { socket: Socket -> declareHashesSeme(socket) }
    }

    fun declareHashesSeme(socket: Socket): Single<DeclareHashesPacket> {
        Log.v(TAG, "declareHashesSeme")
        return datastore.declareHashesPacket
                .flatMapObservable<DeclareHashesPacket> { declareHashesPacket: DeclareHashesPacket ->
                    DeclareHashesPacket.parseFrom(socket.getInputStream())
                            .subscribeOn(operationsScheduler)
                            .toObservable()
                            .mergeWith(declareHashesPacket.writeToStream(socket.getOutputStream())
                                    .subscribeOn(operationsScheduler))
                }
                .firstOrError()
    }

    fun routingMetadataUke(packets: Flowable<RoutingMetadataPacket>): Observable<RoutingMetadataPacket> {
        return serverSocket
                .flatMapObservable { sock: Socket -> routingMetadataSeme(sock, packets) }
    }

    fun routingMetadataSeme(socket: Socket, packets: Flowable<RoutingMetadataPacket>): Observable<RoutingMetadataPacket> {
        return Observable.just(socket)
                .flatMap { sock: Socket ->
                    RoutingMetadataPacket.parseFrom(sock.getInputStream())
                            .subscribeOn(operationsScheduler)
                            .toObservable()
                            .repeat()
                            .takeWhile(Predicate { routingMetadataPacket: RoutingMetadataPacket ->
                                val end = !routingMetadataPacket.isEmpty
                                if (!end) {
                                    Log.v(TAG, "routingMetadata seme end of stream")
                                }
                                end
                            }) //TODO: timeout here
                            .mergeWith(packets.concatMapCompletable { p: RoutingMetadataPacket ->
                                p.writeToStream(sock.getOutputStream())
                                        .subscribeOn(operationsScheduler)
                            })
                }
    }

    fun identityPacketUke(packets: Flowable<IdentityPacket>): Observable<IdentityPacket> {
        return serverSocket
                .flatMapObservable { sock: Socket -> identityPacketSeme(sock, packets) }
    }

    fun identityPacketSeme(socket: Socket, packets: Flowable<IdentityPacket>): Observable<IdentityPacket> {
        return Single.just(socket)
                .flatMapObservable { sock: Socket ->
                    IdentityPacket.parseFrom(sock.getInputStream(), mContext)
                            .subscribeOn(operationsScheduler)
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
                                p.writeToStream(sock.getOutputStream())
                                        .subscribeOn(operationsScheduler)
                                        .doOnComplete { Log.v(TAG, "wrote single identity packet") }
                            })
                }.doOnComplete { Log.v(TAG, "identity packets complete") }
    }

    fun awaitPeersChanged(timeout: Int, unit: TimeUnit): Completable {
        return mBroadcastReceiver.observePeers()
                .firstOrError()
                .timeout(timeout.toLong(), unit, operationsScheduler)
                .ignoreElement()
    }

    fun awaitConnection(timeout: Int): Single<WifiP2pInfo> {
        return mBroadcastReceiver.observeConnectionInfo()
                .takeUntil { info: WifiP2pInfo -> info.groupFormed && !info.isGroupOwner }
                .lastOrError()
                .timeout(timeout.toLong(), TimeUnit.SECONDS, operationsScheduler)
                .doOnSuccess { info: WifiP2pInfo -> Log.v(TAG, "connect to group returned: " + info!!.groupOwnerAddress) }
                .doOnError { err: Throwable -> Log.e(TAG, "connect to group failed: $err") }
                .doFinally { groupConnectInProgress.set(false) }
    }

    override fun bootstrapFromUpgrade(upgradeRequest: BootstrapRequest): Single<HandshakeResult> {
        Log.v(TAG, "bootstrapFromUpgrade: " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME)
                + " " + upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE) + " "
                + upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE))
        return if (upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                === ConnectionRole.ROLE_UKE) {
            retryDelay(createGroup(
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE)
            ),10, 1)
                    .andThen(
                            routingMetadataUke(Flowable.just(RoutingMetadataPacket.newBuilder().setEmpty().build()))
                                    .ignoreElements()
                    )
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
                    ).flatMap { stats: HandshakeResult? ->
                        declareHashesUke()
                                .doOnSuccess { p: DeclareHashesPacket? -> Log.v(TAG, "received declare hashes packet uke") }
                                .flatMap { declareHashesPacket: DeclareHashesPacket ->
                                    readBlockDataUke()
                                            .toObservable()
                                            .mergeWith(
                                                    writeBlockDataUke(
                                                            datastore.getTopRandomMessages(
                                                                    preferences.getInt(mContext.getString(R.string.pref_blockdatacap), 100),
                                                                    declareHashesPacket
                                                            ).toFlowable(BackpressureStrategy.BUFFER)
                                                    ))
                                            .reduce(stats, { obj: HandshakeResult?, stats: HandshakeResult? -> obj!!.from(stats) })
                                }
                    }
        } else if (upgradeRequest.getSerializableExtra(WifiDirectBootstrapRequest.KEY_ROLE)
                === ConnectionRole.ROLE_SEME) {
            retryDelay(connectToGroup(
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_NAME),
                    upgradeRequest.getStringExtra(WifiDirectBootstrapRequest.KEY_PASSPHRASE),
                    120
            ), 10, 1)
                    .flatMap { info: WifiP2pInfo ->
                        getTcpSocket(info.groupOwnerAddress)
                                .flatMap { socket: Socket ->
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
                                            .flatMap { p: ArrayList<IdentityPacket> ->
                                                datastore.insertIdentityPacket(p).toSingleDefault(
                                                        HandshakeResult(
                                                                p.size,
                                                                0,
                                                                HandshakeResult.TransactionStatus.STATUS_SUCCESS
                                                        )
                                                )
                                            }
                                            .flatMap { stats: HandshakeResult ->
                                                declareHashesSeme(socket)
                                                        .doOnSuccess { p: DeclareHashesPacket? -> Log.v(TAG, "received declare hashes packet seme") }
                                                        .flatMapObservable { declareHashesPacket: DeclareHashesPacket ->
                                                            readBlockDataSeme(socket)
                                                                    .toObservable()
                                                                    .mergeWith(writeBlockDataSeme(socket, datastore.getTopRandomMessages(32, declareHashesPacket)
                                                                            .toFlowable(BackpressureStrategy.BUFFER)))
                                                        }
                                                        .reduce(stats, { obj: HandshakeResult, st: HandshakeResult -> obj.from(st) })
                                            }
                                }
                    }
                    .doOnSubscribe { disp: Disposable? -> Log.v(TAG, "subscribed to writeBlockData") }
        } else {
            Single.error(IllegalStateException("invalid role"))
        }
    }

    private fun <T> retryDelay(observable: Observable<T>, count: Int, seconds: Int): Observable<T> {
        return observable
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors: Observable<Throwable> ->
                    errors
                            .zipWith(Observable.range(1, count), BiFunction { _: Throwable, i: Int -> i })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    private fun retryDelay(completable: Completable, count: Int, seconds: Int): Completable {
        return completable
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors: Flowable<Throwable> ->
                    errors
                            .zipWith(Flowable.range(1, count), BiFunction { _: Throwable, i: Int -> i })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    private fun <T> retryDelay(single: Single<T>, count: Int, seconds: Int): Single<T> {
        return single
                .doOnError { err -> Log.e(TAG, "retryDelay caught exception: $err")}
                .retryWhen { errors: Flowable<Throwable> ->
                    errors
                            .zipWith(Flowable.range(1, count), BiFunction { _: Throwable, i: Int -> i!! })
                            .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
                }
    }

    private fun writeBlockDataSeme(
            socket: Socket,
            stream: Flowable<BlockDataStream>
    ): Completable {
        return stream.concatMapCompletable { blockDataStream: BlockDataStream ->
            blockDataStream.headerPacket.writeToStream(socket.getOutputStream())
                    .doOnComplete { Log.v(TAG, "wrote headerpacket to client socket") }
                    .andThen(
                            blockDataStream.sequencePackets
                                    .doOnNext { packet: BlockSequencePacket? -> Log.v(TAG, "seme writing sequence packet: " + packet!!.getmData()!!.size()) }
                                    .concatMapCompletable { sequencePacket: BlockSequencePacket? -> sequencePacket!!.writeToStream(socket.getOutputStream()) }
                                    .doOnComplete { Log.v(TAG, "wrote sequence packets to client socket") }
                    )
        }
    }

    private fun writeBlockDataUke(
            stream: Flowable<BlockDataStream>
    ): Completable {
        return serverSocket
                .flatMapCompletable { socket: Socket ->
                    stream.doOnSubscribe { disp: Subscription? -> Log.v(TAG, "subscribed to BlockDataStream observable") }
                            .doOnNext { p: BlockDataStream? -> Log.v(TAG, "writeBlockData processing BlockDataStream") }
                            .concatMapCompletable { blockDataStream: BlockDataStream ->
                                blockDataStream.headerPacket.writeToStream(socket.getOutputStream())
                                        .subscribeOn(operationsScheduler)
                                        .doOnComplete { Log.v(TAG, "server wrote header packet") }
                                        .andThen(
                                                blockDataStream.sequencePackets
                                                        .doOnNext { packet: BlockSequencePacket? -> Log.v(TAG, "uke writing sequence packet: " + packet!!.getmData()!!.size()) }
                                                        .concatMapCompletable { blockSequencePacket: BlockSequencePacket? ->
                                                            blockSequencePacket!!.writeToStream(socket.getOutputStream())
                                                                    .subscribeOn(operationsScheduler)
                                                        }
                                                        .doOnComplete { Log.v(TAG, "server wrote sequence packets") }
                                        )
                            }
                }
    }

    private fun readBlockDataUke(): Single<HandshakeResult> {
        return serverSocket
                .toFlowable()
                .flatMap<BlockDataStream> { socket: Socket ->
                    BlockHeaderPacket.parseFrom(socket.getInputStream())
                            .subscribeOn(operationsScheduler)
                            .toFlowable()
                            .takeWhile { stream: BlockHeaderPacket ->
                                val end = !stream.isEndOfStream
                                if (end) {
                                    Log.v(TAG, "uke end of stream")
                                }
                                end
                            } //TODO: timeout here
                            .flatMap { headerPacket: BlockHeaderPacket ->
                                Flowable.range(0, headerPacket.hashList!!.size)
                                        .map {
                                            BlockDataStream(
                                                    headerPacket,
                                                    BlockSequencePacket.parseFrom(socket.getInputStream())
                                                            .subscribeOn(operationsScheduler)
                                                            .repeat(headerPacket.hashList.size.toLong())
                                                            .doOnNext {
                                                                packet: BlockSequencePacket ->
                                                                Log.v(TAG, "uke reading sequence packet: " + packet.getmData()!!.size())
                                                            }
                                                            .doOnComplete { Log.v(TAG, "server read sequence packets") }
                                            )
                                        }
                            }
                }
                .concatMapSingle { m: BlockDataStream -> datastore.insertMessage(m).andThen(m.await()).toSingleDefault(0) }
                .reduce { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
                .map { i: Int? -> HandshakeResult(0, i!!, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                .toSingle()
    }

    private val serverSocket: Single<Socket>
        private get() = socketFactory.create(SCATTERBRAIN_PORT)
                .flatMapObservable { obj: InterceptableServerSocket -> obj.observeConnections() }
                .map<Socket> { conn -> conn.socket }
                .firstOrError()
                .doOnSuccess { n: Socket? -> Log.v(TAG, "accepted server socket") }

    private fun readBlockDataSeme(
            socket: Socket
    ): Single<HandshakeResult> {
        return Single.fromCallable<Single<BlockHeaderPacket>> {
            BlockHeaderPacket.parseFrom(socket.getInputStream())
                    .subscribeOn(operationsScheduler)
        }
                .flatMap { obs: Single<BlockHeaderPacket> -> obs }
                .toFlowable()
                .takeWhile { stream: BlockHeaderPacket ->
                    val end = !stream.isEndOfStream
                    if (end) {
                        Log.v(TAG, "seme end of stream")
                    }
                    end
                }
                .flatMap { header: BlockHeaderPacket ->
                    Flowable.range(0, header.hashList!!.size)
                            .map {
                                BlockDataStream(
                                        header,
                                        BlockSequencePacket.parseFrom(socket.getInputStream())
                                                .subscribeOn(operationsScheduler)
                                                .repeat(header.hashList.size.toLong())
                                                .doOnNext{ packet: BlockSequencePacket ->
                                                    Log.v(TAG, "seme reading sequence packet: " + packet.getmData()!!.size())
                                                }
                                                .doOnComplete { Log.v(TAG, "seme complete read sequence packets") }
                                )
                            }
                }
                .concatMapSingle { m: BlockDataStream -> datastore.insertMessage(m).andThen(m.await()).subscribeOn(operationsScheduler).toSingleDefault(0) }
                .reduce { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
                .map { i: Int? -> HandshakeResult(0, i!!, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                .toSingle()
    }

    companion object {
        private const val SCATTERBRAIN_PORT = 7575
        private val socketFactory = InterceptableServerSocketFactory()
        private val groupOperationInProgress = AtomicReference<Boolean>()
        private val groupConnectInProgress = AtomicReference<Boolean>()
        private val wifidirectDisposable = CompositeDisposable()
        private val tcpServerDisposable = CompositeDisposable()
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
        this.readScheduler = readScheduler
        this.writeScheduler = writeScheduler
        this.operationsScheduler = operationsScheduler
        this.datastore = datastore
        this.preferences = preferences
        groupOperationInProgress.set(false)
        groupConnectInProgress.set(false)
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