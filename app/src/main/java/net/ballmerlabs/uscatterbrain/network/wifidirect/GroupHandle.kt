package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pDeviceList
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterproto.MessageSizeException
import net.ballmerlabs.scatterproto.MessageValidationException
import net.ballmerlabs.uscatterbrain.GroupFinalizer
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.WifiGroupScope
import net.ballmerlabs.uscatterbrain.WifiGroupSubcomponent
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import net.ballmerlabs.uscatterbrain.network.proto.*

@WifiGroupScope
class GroupHandle @Inject constructor(
    val request: WifiDirectBootstrapRequest,
    val receiver: WifiDirectBroadcastReceiver,
    val datastore: ScatterbrainDatastore,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) private val timeoutScheduler: Scheduler,
    @Named(WifiGroupSubcomponent.NamedSchedulers.WIFI_OPERATIONS) private val operationsScheduler: Scheduler,
    private val scheduler: Provider<ScatterbrainScheduler>,
    private val mBroadcastReceiver: WifiDirectBroadcastReceiver,
    private val serverSocketManager: ServerSocketManager,
    private val session: WifiSessionConfig,
    private val socketProvider: SocketProvider,
    private val advertiser: Advertiser,
    private val mContext: Context,
    private val preferences: RouterPreferences,
    private val serverSocket: PortSocket,
    private val groupFinalizer: GroupFinalizer,
) {
    val LOG by scatterLog()
    private val connectedPeers = ConcurrentHashMap<InetSocketAddress, InetSocketAddress>()
    private val connectedAddressSet = ConcurrentHashMap<InetSocketAddress, UUID>()
    private val ukeDispoable = AtomicReference<Disposable>(null)


    //transfer routing metadata packet as UKE
    private fun routingMetadataUke(
        packets: Flowable<RoutingMetadataPacket>,
        socket: Socket,
    ): Observable<RoutingMetadataPacket> {
        return routingMetadataSeme(socket, packets)
    }

    //transfer routing metadata packet as SEME
    private fun routingMetadataSeme(
        socket: Socket,
        packets: Flowable<RoutingMetadataPacket>,
    ): Observable<RoutingMetadataPacket> {
        return Observable.just(socket)
            .flatMap { sock ->
                ScatterSerializable.parseWrapperFromCRC(
                    RoutingMetadataPacketParser.parser,
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
                    }.mergeWith(
                        packets.concatMapCompletable { p ->
                            p.writeToStream(sock.getOutputStream(), operationsScheduler)
                                .flatMapCompletable { v -> v }
                        }.toObservable()
                    )
            }.doOnComplete { LOG.w("routingMetadata Complete") }
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
                    DeclareHashesPacketParser.parser,
                    socket.getInputStream(),
                    operationsScheduler
                )
                    .toObservable()
                    .mergeWith(
                        declareHashesPacket.writeToStream(
                            socket.getOutputStream(),
                            operationsScheduler
                        ).flatMapCompletable { v -> v }
                    )
            }
            .firstOrError()
    }

    //transfer identity packet as UKE
    private fun identityPacketUke(
        packets: Flowable<IdentityPacket>,
        socket: Socket,
    ): Observable<IdentityPacket> {
        return identityPacketSeme(socket, packets)
    }

    //transfer identity packet as SEME
    private fun identityPacketSeme(
        socket: Socket,
        packets: Flowable<IdentityPacket>,
    ): Observable<IdentityPacket> {
        return Single.just(socket)
            .flatMapObservable { sock ->
                ScatterSerializable.parseWrapperFromCRC(
                    IdentityPacketParser.parser,
                    sock.getInputStream(),
                    operationsScheduler
                )
                    .retryWhen { err ->
                        err.flatMap { e ->
                            when (e) {
                                is MessageSizeException -> Flowable.just(e)
                                is net.ballmerlabs.scatterproto.MessageValidationException -> Flowable.just(e)
                                else -> Flowable.error(e)
                            }
                        }
                    }
                    .toObservable()
                    .repeat()
                    .subscribeOn(operationsScheduler)
                    .takeWhile { identityPacket ->
                        val end = !identityPacket.isEnd
                        if (!end) {
                            LOG.v("identitypacket seme end of stream")
                        }
                        end
                    }.mergeWith(
                        packets.concatMapCompletable { p ->
                            p.writeToStream(sock.getOutputStream(), operationsScheduler)
                                .flatMapCompletable { v -> v }
                                .doOnComplete { LOG.v("wrote single identity packet") }
                        }.toObservable()
                    )
            }.doOnComplete { LOG.w("identity packets complete") }
    }

    /*
 * read blockdata packets as UKE and stream into datastore. Even if a transfer is interrupted we should still have
 * the files/metadata from packets we received
 */
    private fun readBlockDataUke(socket: Socket): Single<HandshakeResult> {
        return ScatterSerializable.parseWrapperFromCRC(
            BlockHeaderPacketParser.parser,
            socket.getInputStream(),
            operationsScheduler
        ).retryWhen { err ->
            err.flatMap { e ->
                when (e) {
                    is MessageSizeException -> Flowable.just(e)
                    is net.ballmerlabs.scatterproto.MessageValidationException -> Flowable.just(e)
                    else -> Flowable.error(e)
                }
            }
        }
            .doOnSuccess { header -> LOG.v("uke reading header ${header.userFilename}") }
            .flatMap { headerPacket ->
                LOG.v("uke read header success")
                if (headerPacket.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = WifiDirectRadioModule.BlockDataStream(
                        headerPacket,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacketParser.parser,
                            socket.getInputStream(),
                            operationsScheduler,
                        )
                            .repeat()
                            .takeWhile { p -> !p.isEnd }
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
    }

    /*
     * read blockdata packets as SEME and stream into datastore. Even if a transfer is interrupted we should still have
     * the files/metadata from packets we received
     */
    private fun readBlockDataSeme(
        socket: Socket,
    ): Single<HandshakeResult> {
        return ScatterSerializable.parseWrapperFromCRC(
            BlockHeaderPacketParser.parser,
            socket.getInputStream(),
            operationsScheduler
        )
            .retryWhen { err ->
                err.flatMap { e ->
                    when (e) {
                        is MessageSizeException -> Flowable.just(e)
                        is net.ballmerlabs.scatterproto.MessageValidationException -> Flowable.just(e)
                        else -> Flowable.error(e)
                    }
                }
            }
            .doOnSuccess { header -> LOG.v("seme reading header ${header.userFilename}") }
            .flatMap { header ->
                if (header.isEndOfStream) {
                    Single.just(0)
                } else {
                    val m = WifiDirectRadioModule.BlockDataStream(
                        header,
                        ScatterSerializable.parseWrapperFromCRC(
                            BlockSequencePacketParser.parser,
                            socket.getInputStream(),
                            operationsScheduler
                        )
                            .repeat()
                            .takeWhile { p -> !p.isEnd }
                            .doOnNext { packet ->
                                LOG.v("seme reading sequence packet: ${packet.data.size} ${packet.isEnd}")
                            }
                            .doOnComplete { LOG.v("seme complete read sequence packets") },
                        datastore.cacheDir
                    )
                    datastore.insertMessage(m).andThen(m.await())
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

    //transfer blockdata packets as SEME
    private fun writeBlockDataSeme(
        socket: Socket,
        stream: Flowable<WifiDirectRadioModule.BlockDataStream>,
    ): Completable {
        return stream.concatMapCompletable { blockDataStream ->
            blockDataStream.headerPacket.writeToStream(
                socket.getOutputStream(),
                operationsScheduler
            ).flatMapCompletable { c ->
                c.doOnComplete { LOG.v("wrote headerpacket to client socket") }
                    .andThen(
                        blockDataStream.sequencePackets
                            .doOnNext { packet -> LOG.v("seme writing sequence packet: " + packet!!.data.size) }
                            .concatMapCompletable { sequencePacket ->
                                sequencePacket.writeToStream(
                                    socket.getOutputStream(),
                                    operationsScheduler
                                ).flatMapCompletable { v -> v }
                            }
                            .doOnComplete { LOG.v("wrote sequence packets to client socket") }
                    )
                    .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
            }
        }
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
                    preferences.getInt(
                        mContext.getString(R.string.pref_identitycap),
                        200
                    )
                        .onErrorReturnItem(200)
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


    private fun sendSelfIp(socket: Socket, self: UUID, port: Int): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder(self)
                .addAddress(advertiser.getHashLuid(), InetSocketAddress(socket.localAddress, port))
                .build()

            builder.writeToStream(socket.getOutputStream(), operationsScheduler)
                .toSingle()
                .flatMapCompletable { v -> v }
                .andThen(
                    ScatterSerializable.parseWrapperFromCRC(
                        IpAnnouncePacketParser.parser,
                        socket.getInputStream(),
                        operationsScheduler
                    )
                )


        }
    }

    fun semeServer(): Flowable<HandshakeResult> {
        return Flowable.defer {
            serverSocket.accept(operationsScheduler)
                .retry()
                .repeat()
                .observeOn(operationsScheduler)
                .timeout(
                    60,
                    TimeUnit.SECONDS,
                    timeoutScheduler
                ) //TODO: remove hardcoded time
                .onErrorResumeNext(Flowable.empty())
                .flatMapSingle { s ->
                    bootstrapUkeSocket(s.socket)
                        .subscribeOn(operationsScheduler)
                        .doOnError { err -> LOG.w("seme bootstrapUkeSocket failed $err") }
                        .onErrorReturnItem(
                            HandshakeResult(
                                0,
                                0,
                                HandshakeResult.TransactionStatus.STATUS_FAIL
                            )
                        )
                }
        }
    }

    fun bootstrapSeme(
        self: UUID,
    ): Completable {
        return socketProvider.getSocket(
            session.wifiDirectInfo.groupOwnerAddress!!,
            request.port,
            advertiser.getHashLuid()
        ).retryDelay(5, 1)
            .flatMapPublisher { ownerSocket ->
                LOG.v("seme got send ip socket: ${ownerSocket.socket.remoteSocketAddress}, ${ownerSocket.socket.port}")
                sendSelfIp(ownerSocket.socket, self, request.port)
                    .subscribeOn(operationsScheduler)
                    .doOnError { err -> LOG.w("seme sendSelfIp failed $err") }
                    .timeout(50, TimeUnit.SECONDS, timeoutScheduler)
                    .flatMapPublisher { packet ->
                        val size = packet.addresses.size.toLong()
                        LOG.v("seme got ip announce from uke, connected size: $size")
                        bootstrapSemeSocket(ownerSocket.socket)
                            .toFlowable()
                            .mergeWith(Flowable.fromIterable(packet.addresses.values)
                                .flatMapSingle { peerAddr ->
                                    LOG.w("bootstrapping proxy peer ${peerAddr.address.address} ${peerAddr.address.port}")
                                    socketProvider.getSocket(
                                        peerAddr.address.address,
                                        peerAddr.address.port,
                                        advertiser.getHashLuid()
                                    ).retryDelay(
                                        20, 1
                                    )
                                        .flatMap { sock ->
                                            bootstrapSemeSocket(
                                                sock.socket
                                            )
                                                .doOnError { err ->
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
                            .subscribeOn(operationsScheduler)
                            .doOnError { err -> LOG.w("seme bootstrapSemeSocket failed $err") }

                    }.flatMapSingle { v ->
                        scheduler.get().broadcastTransactionResult(v)
                            .toSingleDefault(v)
                    }
            }.ignoreElements()
            .timeout(60, TimeUnit.SECONDS, timeoutScheduler)
    }

    private fun sendConnectedIps(sock: Socket, selfLuid: UUID): Single<IpAnnouncePacket> {
        return Single.defer {
            val builder = IpAnnouncePacket.newBuilder(selfLuid)
            //    builder.addAddress(advertiser.getHashLuid(), InetSocketAddress(sock.localAddress, sock.localPort))

            ScatterSerializable.parseWrapperFromCRC(
                IpAnnouncePacketParser.parser,
                sock.getInputStream(),
                operationsScheduler
            )
                .map { p ->
                    p.addresses.forEach { addr ->
                        LOG.w("I see address ${addr.component2().address}")
                        connectedAddressSet[addr.component2().address] = addr.component1()
                    }
                    updateConnectedPeers()
                    connectedPeers
                        .filter { v -> v.key.address == sock.localAddress }
                        .forEach { v ->
                            builder.addAddress(
                                connectedAddressSet[v.value]!!,
                                v.value
                            )
                        }
                    LOG.e("sendConnectedIps ${connectedPeers.size} ${sock.localAddress}")
                    p
                }.flatMap { p ->
                    builder.build()
                        .writeToStream(sock.getOutputStream(), operationsScheduler)
                        .toSingle()
                        .flatMapCompletable { v -> v }
                        .toSingleDefault(p)
                }
        }.doOnSuccess { LOG.e("sendConnectedIps complete") }
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

    @Synchronized
    fun bootstrapUke(
        selfLuid: UUID,
    ) {
        val disp = ukeDispoable.get()
        if (disp != null) {
            LOG.w("bootstrapUke  already running!")
            return
        }
        val d = Completable.defer {
            LOG.w("uke got socket ${serverSocket.socket.localPort}")
            serverSocket.accept(operationsScheduler)
                .observeOn(operationsScheduler)
                .mergeWith(Completable.defer {
                    advertiser.setAdvertisingLuid(luid = advertiser.getHashLuid())
                })
                .doOnCancel { LOG.e("uke group handle canceled canceled") }
                .doOnError { err -> LOG.w("uke socket error $err, probably just a disconnect") }
                .doOnNext { v -> LOG.w("uke socket accept! ${v.socket.remoteSocketAddress}") }
                .flatMapSingle { sock ->
                    sendConnectedIps(sock.socket, selfLuid)
                        .subscribeOn(operationsScheduler)
                        .ignoreElement()
                        .andThen(bootstrapUkeSocket(sock.socket)
                            .subscribeOn(operationsScheduler)
                            .doOnError { err -> LOG.w("uke bootstrapUkeSocket failed $err") })
                        .flatMap { v ->
                            scheduler.get().broadcastTransactionResult(v)
                                .toSingleDefault(v)
                        }.onErrorReturnItem(
                            HandshakeResult(
                                0,
                                0,
                                HandshakeResult.TransactionStatus.STATUS_FAIL
                            )
                        )
                }
                .ignoreElements()
                .doFinally {
                    LOG.w("bootstrapUke completed SOMEHOW: ${serverSocket.socket.localPort}")
                }

        }.subscribe(
            { },
            { e ->
                LOG.w("uke process err $e")
                mBroadcastReceiver.removeCurrentGroup()
            }
        )
        ukeDispoable.getAndSet(d)?.dispose()
    }

    //transfer blockdata packets as UKE
    private fun writeBlockDataUke(
        stream: Flowable<WifiDirectRadioModule.BlockDataStream>,
        socket: Socket,
    ): Completable {
        return stream.doOnSubscribe { LOG.v("subscribed to BlockDataStream observable") }
            .doOnNext { LOG.v("writeBlockData processing BlockDataStream") }
            .concatMapCompletable { blockDataStream ->
                blockDataStream.headerPacket.writeToStream(
                    socket.getOutputStream(),
                    operationsScheduler
                ).flatMapCompletable { c ->
                    c.doOnComplete { LOG.v("server wrote header packet") }
                        .andThen(
                            blockDataStream.sequencePackets
                                .doOnNext { packet -> LOG.v("uke writing sequence packet: " + packet.data.size) }
                                .concatMapCompletable { blockSequencePacket ->
                                    blockSequencePacket.writeToStream(
                                        socket.getOutputStream(),
                                        operationsScheduler
                                    ).flatMapCompletable { c -> c }
                                }
                                .doOnComplete { LOG.v("server wrote sequence packets") }
                        )
                        .andThen(datastore.incrementShareCount(blockDataStream.headerPacket))
                }
            }.doOnComplete { LOG.v("writeBlockDataUke complete") }
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
                                .subscribeOn(operationsScheduler)
                                .toObservable()
                                .mergeWith(
                                    preferences.getInt(
                                        mContext.getString(R.string.pref_blockdatacap),
                                        100
                                    )
                                        .onErrorReturnItem(100)
                                        .flatMapObservable { v ->
                                            writeBlockDataUke(
                                                datastore.getTopRandomMessages(
                                                    v,
                                                    declareHashesPacket
                                                ).toFlowable(BackpressureStrategy.BUFFER),
                                                socket
                                            ).subscribeOn(operationsScheduler)
                                                .toObservable()
                                        }
                                )
                                .reduce(stats) { obj, stats -> obj.from(stats) }
                        }
                }
        }.doOnSuccess { LOG.v("bootstrapUkeSocket complete") }
    }

    fun shutdownUke() {
        ukeDispoable.getAndSet(null)?.dispose()
    }

    @Throws(Throwable::class)
    protected fun finalize() {
        LOG.w("finalizing wifi groups")
        ukeDispoable.getAndSet(null)?.dispose()
        groupFinalizer.onFinalize()
    }

}