package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.exceptions.BleScanException
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.AckPacket
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

// deca
data class Optional<T>(
    val item: T? = null
) {
    val isPresent: Boolean
        get() = item != null

    companion object {
        fun <T> of(v: T): Optional<T> {
            return Optional(v)
        }

        fun <T> empty(): Optional<T> {
            return Optional(null)
        }
    }
}

/**
 * Bluetooth low energy transport implementation.
 * This transport provides devices discovery, data transfer,
 * and bootstrapping to other transports.
 *
 * LOGic is implemented as a finite state machine to simplify
 * potential modification of protocol behavior.
 *
 * It should be noted that due to quirks in the way bluetooth LE is
 * implemented on android, this transport is unusually complex.
 * The basic idea is to connect to a device via bluetooth LE and establish
 * dual gatt connections in both directions to send protobuf blobs using indications.
 *
 * Dual connections are required because only the GATT server can use
 * notifications/indications, and notifications/indications are the only way to
 * send streams of data efficiently
 *
 * It is important to note that a physical LE connection is NOT the same as a
 * GATT connection. We only want one physical radio connection but two GATT connections.
 * if for some reason we end up connecting twice due to a race condition, we need to identity and
 * throw out one of the connections. While matching duplication connections by mac address is a
 * trivial and correct way of discarding duplicate connections, we cannot do that here because
 * android does not guarantee that the adapter mac will not be randomized between connection
 * attempts
 *
 * Instead of mac, we use a randomly generated uuid called an "LUID". Unfortunately this means we
 * must be capable of servicing multiple concurrent connection up to the exchange of LUID values
 */
@ScatterbrainTransactionScope
class BluetoothLERadioModuleImpl @Inject constructor(
    private val mContext: Context,
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val computeScheduler: Scheduler,
    private val mClient: RxBleClient,
    private val wifiDirectRadioModule: WifiDirectRadioModule,
    private val datastore: ScatterbrainDatastore,
    private val preferences: RouterPreferences,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val firebase: FirebaseWrapper,
    private val state: LeState,
    private val advertiser: Advertiser,
    private val managedGattServer: ManagedGattServer,
    private val broadcastReceiverState: BroadcastReceiverState
) : BluetoothLEModule {
    private val LOG by scatterLog()

    private val discoveryPersistent = AtomicReference(false)

    private val discoveryDispoable = AtomicReference<Disposable>()

    private val transactionCompleteRelay = PublishRelay.create<HandshakeResult>()

    private val transactionErrorRelay = PublishRelay.create<Throwable>()
    private val transactionInProgressRelay = BehaviorRelay.create<Boolean>()


    companion object {
        const val LUID_RANDOMIZE_DELAY = 400

        // scatterbrain service uuid. This is the same for every scatterbrain router.
        val SERVICE_UUID: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")

        // GATT characteristic uuid for semaphor used for a device to  lock a channel.
        // This is to overcome race conditions caused by the statefulness of the GATT DB
        // we really shouldn't need this but android won't let us have two GATT DBs
        val UUID_SEMAPHOR: UUID = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")

        // characteristic to initiate a session
        val UUID_HELLO: UUID = UUID.fromString("5d1b424e-ff15-49b4-b557-48274634a01a")

        // number of channels. This can be increased or decreased for performance
        val NUM_CHANNELS = 8

        fun incrementUUID(uuid: UUID, i: Int): UUID {
            val buffer = ByteBuffer.allocate(16)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            val b = BigInteger(buffer.array()).add(BigInteger.valueOf(i.toLong()))
            val out = ByteBuffer.wrap(b.toByteArray())
            val high = out.long
            val low = out.long
            return UUID(high, low)
        }

        fun uuid2bytes(uuid: UUID?): ByteArray? {
            uuid ?: return null
            val buffer = ByteBuffer.allocate(16)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            return buffer.array()
        }

        fun bytes2uuid(bytes: ByteArray?): UUID? {
            bytes ?: return null
            val buffer = ByteBuffer.wrap(bytes)
            val high = buffer.long
            val low = buffer.long
            return UUID(high, low)
        }
    }

    private val gattServerDisposable = AtomicReference(CompositeDisposable())


    init {
        LOG.e("init")
        observeTransactionComplete()
    }


    private fun observeTransactionComplete() {
        val d = transactionCompleteRelay.subscribe(
            {
                LOG.v("transaction complete, randomizing luid")
            }
        ) { err ->
            LOG.e("error in transactionCompleteRelay $err")
        }
        gattServerDisposable.get()?.add(d)
    }

    private val powerSave: String?
        get() = preferences.getString(
            mContext.getString(R.string.pref_powersave),
            mContext.getString(R.string.powersave_active)
        )

    private fun parseScanMode(): Int {
        return when (powerSave) {
            mContext.getString(R.string.powersave_active) -> {
                LOG.e("scan mode lower power")
                ScanSettings.SCAN_MODE_LOW_POWER
            }
            mContext.getString(R.string.powersave_opportunistic) -> {
                LOG.e("scan mode opportunistic")
                ScanSettings.SCAN_MODE_OPPORTUNISTIC
            }
            else -> {
                -1 //scan disabled
            }
        }
    }


    /**
     * Select the bootstrap protocol we should vote for in leader election
     * Currently, this just tests if wifi direct is broken and falls back
     * to BLE if it is.
     */
    private fun selectProvides(): Single<AdvertisePacket.Provides> {
        return wifiDirectRadioModule.wifiDirectIsUsable()
            .doOnSuccess { p -> LOG.e("selectProvides $p") }
            .map { p -> if (p) AdvertisePacket.Provides.WIFIP2P else AdvertisePacket.Provides.BLE }
    }

    /**
     * initialize the finite state machine. This is VERY important and dictates the
     * bluetooth LE transport's behavior for the entire protocol.
     * the LeDeviceSession object holds the stages and stage transition LOGic, and is
     * executed when a device connects.
     *
     * At a bare minimum session.stage should be set to the starting state and
     * it should be verified that STAGE_EXIT should be reachable without deadlocks
     * After state exit, one of three things should happen
     * 1. the transport errors out and the devices are disconnected
     * 2. the transport bootstraps to another transport and awaits exit
     * 3. the transport transfers messages and identities and then exits
     *
     * This is decided by the leader election process currently
     */
    private fun initializeProtocol(
        s: LeDeviceSession,
        defaultState: String,
    ): Single<LeDeviceSession> {
        LOG.v("initialize protocol")
        return Single.just(s)
            .map { session ->
                /*
                 * luid stage reveals unhashed packets after all hashed packets are collected
                 * hashes are compared to unhashed packets
                 */
                session.addStage(
                    TransactionResult.STAGE_LUID,
                    { serverConn ->
                        LOG.v("gatt server luid stage")
                        serverConn.serverNotify(session.luidStage.selfUnhashedPacket)
                            .doOnError { err -> LOG.e("luid server failed $err") }
                            .toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        LOG.v("gatt client luid stage")
                        conn.readLuid()
                            .doOnSuccess { luidPacket ->
                                LOG.v("client handshake received unhashed luid packet: " + luidPacket.luidVal)
                                session.luidStage.setPacket(luidPacket)
                            }
                            .doOnError { err ->
                                LOG.e("error while receiving luid packet: $err")
                                err.printStackTrace()
                            }
                            .flatMapCompletable { luidPacket ->
                                session.luidStage.verifyPackets()
                                    .doOnComplete {
                                        LOG.v("successfully verified luid packet")
                                        session.luidMap[session.device.address] = luidPacket.luidVal
                                    } //TODO: stop this
                            }
                            .toSingleDefault(
                                TransactionResult.of<BootstrapRequest>(
                                    TransactionResult.STAGE_ADVERTISE
                                )
                            )
                            .doOnError { err ->
                                LOG.e("luid hash verify failed: $err")
                                err.printStackTrace()
                            }
                    })

                /*
                 * if no one cheats and sending luids, we exchange advertise packets.
                 * This is really boring currently because all scatterbrain routers offer the same capabilities
                 */
                session.addStage(
                    TransactionResult.STAGE_ADVERTISE,
                    { serverConn ->
                        LOG.v("gatt server advertise stage")
                        serverConn.serverNotify(AdvertiseStage.self)
                            .toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        LOG.v("gatt client advertise stage")
                        conn.readAdvertise()
                            .doOnSuccess { LOG.v("client handshake received advertise packet") }
                            .doOnError { err -> LOG.e("error while receiving advertise packet: $err") }
                            .map { advertisePacket ->
                                session.advertiseStage.addPacket(advertisePacket)
                                TransactionResult.of(TransactionResult.STAGE_ELECTION_HASHED)
                            }
                    })

                /*
                 * the leader election state is really complex, it is a good idea to check the documentation in
                 * VotingStage.kt to figure out how this works.
                 */
                session.addStage(
                    TransactionResult.STAGE_ELECTION_HASHED,
                    { serverConn ->
                        selectProvides().flatMap { provides ->
                            LOG.v("gatt server election hashed stage")
                            val packet = session.votingStage.getSelf(true, provides)
                            session.votingStage.addPacket(packet)
                            serverConn.serverNotify(packet)
                                .toSingleDefault(TransactionResult.empty())
                        }
                    },
                    { conn ->
                        LOG.v("gatt client election hashed stage")
                        conn.readElectLeader()
                            .doOnSuccess { p -> LOG.v("client handshake received hashed election packet ${p.provides}") }
                            .doOnError { err -> LOG.e("error while receiving election packet: $err") }
                            .map { electLeaderPacket ->
                                session.votingStage.addPacket(electLeaderPacket)
                                TransactionResult.of(TransactionResult.STAGE_ELECTION)
                            }

                    })

                /*
                 * see above
                 * Once the election stage is finished we move on to the actions decided by the result of the election
                 */
                session.addStage(
                    TransactionResult.STAGE_ELECTION,
                    { serverConn ->
                        LOG.v("gatt server election stage")
                        Single.just(session.luidStage.selfUnhashedPacket)
                            .flatMap { luidPacket ->
                                selectProvides().flatMapCompletable { provides ->
                                    LOG.v("server sending unhashed provides $provides")
                                    val packet = session.votingStage.getSelf(false, provides)
                                    packet.tagLuid(luidPacket.luidVal)
                                    session.votingStage.addPacket(packet)
                                    serverConn.serverNotify(packet)
                                        .doFinally {
                                            session.votingStage.serverPackets.onComplete()
                                        }
                                }
                                    .doOnError { err -> LOG.e("election server error $err") }
                                    .toSingleDefault(TransactionResult.empty())
                            }
                    },
                    { conn ->
                        LOG.v("gatt client election stage")
                        conn.readElectLeader()
                            .flatMapCompletable { electLeaderPacket ->
                                LOG.v("gatt client received elect leader packet")
                                electLeaderPacket.tagLuid(session.luidMap[session.device.address])
                                session.votingStage.addPacket(electLeaderPacket)
                                session.votingStage.serverPackets.andThen(session.votingStage.verifyPackets())
                            }
                            .andThen(session.votingStage.determineUpgrade())
                            .map { provides ->
                                LOG.v("election received provides: $provides")
                                val role: ConnectionRole =
                                    if (session.votingStage.selectSeme() == session.luidStage.selfUnhashed) {
                                        ConnectionRole.ROLE_SEME
                                    } else {
                                        ConnectionRole.ROLE_UKE
                                    }
                                LOG.v("selected role: $role")
                                session.role = role
                                session.setUpgradeStage(provides)
                                when (provides) {
                                    AdvertisePacket.Provides.INVALID -> {
                                        LOG.e("received invalid provides")
                                        TransactionResult.of<BootstrapRequest>(
                                            TransactionResult.STAGE_SUSPEND
                                        )
                                    }
                                    AdvertisePacket.Provides.BLE -> {
                                        LOG.e("fallback: bootstrap BLE")
                                        //we should do everything in BLE. slowwwww ;(
                                        TransactionResult.of(
                                            TransactionResult.STAGE_IDENTITY
                                        )
                                    }
                                    AdvertisePacket.Provides.WIFIP2P ->
                                        TransactionResult.of(
                                            TransactionResult.STAGE_UPGRADE
                                        )
                                }
                            }
                            .doOnError { err -> LOG.e("error while receiving packet: $err") }
                            .doOnSuccess { result -> LOG.v("client handshake received election result ${result.stage}") }
                    })

                /*
                 * if the election state decided we should upgrade, move to a new transport using an upgrade
                 * packet exchange with our newly assigned role.
                 * Currently this just means switch to wifi direct
                 */
                session.addStage(
                    TransactionResult.STAGE_UPGRADE,
                    { serverConn ->
                        LOG.v("gatt server upgrade stage")
                        if (session.role == ConnectionRole.ROLE_UKE) {
                            LOG.e("upgrade role UKE")
                            wifiDirectRadioModule.createGroup()
                                .timeout(10, TimeUnit.SECONDS)
                                .flatMap { bootstrap ->
                                    val upgradeStage = session.upgradeStage
                                    if (upgradeStage != null) {
                                        val upgradePacket =
                                            bootstrap.toUpgrade(upgradeStage.sessionID)
                                        serverConn.serverNotify(upgradePacket)
                                            .toSingleDefault(
                                                TransactionResult.of(
                                                    bootstrap,
                                                    TransactionResult.STAGE_TERMINATE
                                                )
                                            )
                                    } else {
                                        Single.error(IllegalStateException("upgrade stage not set while bootstrapping ${session.remoteLuid}"))
                                    }
                                }
                        } else {
                            Single.just(TransactionResult.empty())
                        }
                    },
                    { conn ->
                        LOG.v("gatt client upgrade stage")
                        if (session.role == ConnectionRole.ROLE_SEME) {
                            LOG.e("upgrade role SEME")
                            conn.readUpgrade()
                                .doOnSuccess { p -> LOG.v("client handshake received upgrade packet ${p.metadata.size}") }
                                .doOnError { err -> LOG.e("error while receiving upgrade packet: $err") }
                                .map { upgradePacket ->
                                    if (upgradePacket.provides == AdvertisePacket.Provides.WIFIP2P) {
                                        val request = WifiDirectBootstrapRequest.create(
                                            upgradePacket,
                                            ConnectionRole.ROLE_SEME,
                                            bootstrapRequestProvider.get()
                                        )
                                        TransactionResult.of(
                                            request,
                                            TransactionResult.STAGE_TERMINATE,
                                        )
                                    } else {
                                        TransactionResult.err(
                                            IllegalStateException("invalid provides ${upgradePacket.provides}")
                                        )
                                    }
                                }
                        } else {
                            Single.just(TransactionResult.empty())
                        }
                    })

                /*
                 * if we chose not to upgrade, proceed with exchanging identity packets
                 */
                session.addStage(
                    TransactionResult.STAGE_IDENTITY,
                    { serverConn ->
                        datastore.getTopRandomIdentities(
                            preferences.getInt(mContext.getString(R.string.pref_identitycap), 32)!!
                        )
                            .concatMapCompletable { packet -> serverConn.serverNotify(packet) }
                            .toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        conn.readIdentityPacket()
                            .repeat()
                            .takeWhile { identityPacket ->
                                val end = !identityPacket.isEnd
                                if (!end) {
                                    LOG.v("identitypacket end of stream")
                                }
                                end
                            }
                            .ignoreElements()
                            .toSingleDefault(TransactionResult.of(TransactionResult.STAGE_DECLARE_HASHES))
                    })

                /*
                 * if we chose not to upgrade, exchange delcare hashes packets
                 */
                session.addStage(
                    TransactionResult.STAGE_DECLARE_HASHES,
                    { serverConn ->
                        LOG.v("gatt server declareHashes stage")
                        datastore.declareHashesPacket
                            .flatMapCompletable { packet ->
                                LOG.e("declaredhashes packet ${packet.bytes.size}")
                                serverConn.serverNotify(packet)
                            }
                            .toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        conn.readDeclareHashes()
                            .doOnSuccess { declareHashesPacket ->
                                LOG.v(
                                    "client handshake received declareHashes: " +
                                            declareHashesPacket.hashes.size
                                )
                            }
                            .doOnError { err -> LOG.e("error while receiving declareHashes packet: $err") }
                            .map { declareHashesPacket ->
                                session.setDeclareHashesPacket(declareHashesPacket)
                                TransactionResult.of(TransactionResult.STAGE_BLOCKDATA)
                            }
                    })

                /*
                 * if we chose not to upgrade, exchange messages V E R Y S L O W LY
                 * TODO: put some sort of cap on file size to avoid hanging here for hours
                 */
                session.addStage(
                    TransactionResult.STAGE_BLOCKDATA,
                    { serverConn ->
                        LOG.v("gatt server blockdata stage")
                        session.declareHashes
                            .flatMapCompletable { declareHashesPacket ->
                                datastore.getTopRandomMessages(
                                    preferences.getInt(
                                        mContext.getString(R.string.pref_blockdatacap),
                                        30
                                    )!!,
                                    declareHashesPacket
                                )
                                    .concatMapCompletable { message ->
                                        serverConn.serverNotify(message.headerPacket)
                                            .andThen(message.sequencePackets.concatMapCompletable { packet ->
                                                serverConn.serverNotify(packet)
                                            })
                                    }
                            }.toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        conn.readBlockHeader()
                            .toFlowable()
                            .takeWhile { h -> !h.isEndOfStream }
                            .map { blockHeaderPacket ->
                                LOG.v("header ${blockHeaderPacket.hashList.size}")
                                BlockDataStream(
                                    blockHeaderPacket,
                                    conn.readBlockSequence()
                                        .repeat(blockHeaderPacket.hashList.size.toLong()),
                                    datastore.cacheDir
                                )
                            }
                            .takeUntil { stream ->
                                val end = stream.headerPacket.isEndOfStream
                                if (end) {
                                    LOG.v("uke end of stream")
                                }
                                end
                            }
                            .concatMapSingle { m ->
                                datastore.insertMessage(m).andThen(m.await()).toSingleDefault(0)
                            }
                            .reduce { a, b -> a + b }
                            .toSingle(0)
                            .map { i ->
                                HandshakeResult(
                                    0,
                                    i,
                                    HandshakeResult.TransactionStatus.STATUS_SUCCESS
                                )
                            }
                            .map {
                                //  transactionCompleteRelay.accept(res)
                                TransactionResult.of(TransactionResult.STAGE_TERMINATE)
                            }
                    })

                // set our starting stage
                session.stage = defaultState
                session
            }
    }

    /* attempt to bootstrap to wifi direct using upgrade packet (gatt client version) */
    private fun bootstrapWifiP2p(bootstrapRequest: BootstrapRequest): Single<HandshakeResult> {
        return wifiDirectRadioModule.bootstrapFromUpgrade(bootstrapRequest)
            .doOnError { err ->
                LOG.e("wifi p2p upgrade failed: $err")
                err.printStackTrace()
                transactionCompleteRelay.accept(
                    HandshakeResult(
                        0,
                        0,
                        HandshakeResult.TransactionStatus.STATUS_FAIL
                    )
                )
            }
    }

    override fun clearPeers() {
        LOG.e("clearPeers")
        state.connectionCache.values.forEach { c ->
            c.dispose()
        }
        state.activeLuids.clear()
        state.connectionCache.clear()
    }

    override fun processScanResult(scanResult: ScanResult): Maybe<HandshakeResult> {
        LOG.d("scan result: " + scanResult.bleDevice.macAddress)
        return Maybe.defer {
            val remoteUuid = state.getAdvertisedLuid(scanResult)
            if (remoteUuid != null) {
                state.establishConnectionCached(scanResult.bleDevice, remoteUuid)
                    .flatMapMaybe { cached ->
                        cached.connection
                            .firstOrError()
                            .flatMapMaybe { raw ->
                                LOG.v("attempting to read hello characteristic")
                                    raw.readCharacteristic(UUID_HELLO)
                                        .flatMapMaybe { luid ->
                                            val luidUuid = bytes2uuid(luid)!!
                                            LOG.v("read remote luid from GATT $luidUuid")
                                            initiateOutgoingConnection(
                                                cached,
                                                luidUuid
                                            ).onErrorComplete()
                                        }
                            }
                    }
            } else {
                LOG.e("remote luid was null")
                Maybe.empty()
            }
        }
    }


    override fun initiateOutgoingConnection(
        cachedConnection: CachedLEConnection,
        luid: UUID
    ): Maybe<HandshakeResult> {
        return Maybe.defer {
            transactionInProgressRelay.accept(true)
            LOG.e("initiateOutgoingConnection luid $luid")
            cachedConnection.connection
                .firstOrError()
                .flatMapMaybe { connection ->
                    val hash = getHashUuid(advertiser.myLuid.get())!!
                    LOG.v("writing hashed luid $hash")
                    connection.writeCharacteristic(UUID_HELLO, uuid2bytes(hash)!!)
                        .doOnSuccess { res ->
                            LOG.v("successfully wrote uuid len ${res.size}")
                        }
                        .doOnError { e ->
                            firebase.recordException(e)
                            LOG.e("failed to write characteristic: $e")
                        }
                        .ignoreElement()
                        .andThen(handleConnection(cachedConnection, cachedConnection.device, luid))
                }
        }
            .subscribeOn(computeScheduler)
            .doOnError { err ->
                LOG.v("error in initiateOutgoingConnection $err")
                firebase.recordException(err)
                state.updateDisconnected(luid)
            }
    }

    private fun discoverContinuous(): Observable<ScanResult> {
        return mClient.scanBleDevices(
            ScanSettings.Builder()
                .setScanMode(parseScanMode())
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setShouldCheckLocationServicesState(false)
                .setLegacy(true)
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
    }

    /**
     * start device discovery forever, dispose to cancel.
     * @return observable of HandshakeResult containing transaction stats
     */
    override fun discoverForever(): Observable<HandshakeResult> {
        val discover = discoverContinuous()
            .retryWhen { e -> handleUndocumentedScanThrottling<HandshakeResult>(e) }
            .doOnSubscribe { discoveryPersistent.set(true) }
            .concatMapSingle { res ->
                if (state.shouldConnect(res)) {
                    transactionInProgressRelay.accept(true)
                }
                advertiser.setAdvertisingLuid()
                    .andThen(removeWifiDirectGroup(advertiser.randomizeLuidIfOld()).onErrorComplete())
                    .toSingleDefault(res)
            }
            .filter { res -> state.shouldConnect(res) }
            .concatMapMaybe { scanResult ->
                processScanResult(scanResult)
                    .onErrorComplete()
                    .doOnSuccess {
                        LOG.e(
                            "I DID A DONE! transaction for ${scanResult.bleDevice.macAddress} complete"
                        )
                    }
                    .doOnError { err ->
                        firebase.recordException(err)
                        LOG.e(
                            "transaction for ${scanResult.bleDevice.macAddress} failed: $err"
                        )
                        err.printStackTrace()
                    }
            }
            .doOnComplete { LOG.e("discoverForever completed") }
            .doOnError { err ->
                firebase.recordException(err)
                LOG.e("discoverForever error: $err")
                clearPeers()
            }
            .doFinally { discoveryPersistent.set(false) }
            .subscribeOn(computeScheduler)
            .doOnSubscribe { LOG.e("subscribed discoverForever") }

        return discover
    }

    // Droids are weird and sometimes throttle repeated LE discoveries.
    // if throttled, wait the suggested amount, and in any case don't resubscribe too quickly
    private fun <T> handleUndocumentedScanThrottling(
        e: Observable<Throwable>,
        defaultDelay: Long = 10
    ): Observable<T> {
        return e
            .subscribeOn(computeScheduler)
            .concatMap { err ->
                if (err is BleScanException && err.retryDateSuggestion != null) {
                    val delay = err.retryDateSuggestion!!.time - Date().time
                    LOG.e("undocumented scan throttling. Waiting $delay seconds")
                    Completable.complete().delay(delay, TimeUnit.SECONDS)
                        .andThen(Observable.error(err))
                } else {
                    Completable.complete().delay(defaultDelay, TimeUnit.SECONDS)
                        .andThen(Observable.error(err))
                }
            }
    }

    override fun observeTransactionStatus(): Observable<Boolean> {
        return transactionInProgressRelay.delay(0, TimeUnit.SECONDS, operationsScheduler)
    }

    /**
     * @return a completsable that completes when the current transaction is finished, with optional error state
     */
    override fun awaitTransaction(): Completable {
        return Completable.mergeArray(
            transactionCompleteRelay.firstOrError().ignoreElement(),
            transactionErrorRelay.flatMapCompletable { error -> Completable.error(error) }
        )
    }

    /**
     * @return observable with transaction stats
     */
    override fun observeCompletedTransactions(): Observable<HandshakeResult> {
        return Observable.merge(
            transactionCompleteRelay,
            transactionErrorRelay.flatMap { exception -> throw exception }
        )
    }

    /**
     * kill current scan in progress
     */
    override fun stopDiscover() {
        discoveryPersistent.set(false)
        val d = discoveryDispoable.get()
        d?.dispose()
    }

    override fun removeWifiDirectGroup(shouldRemove: Boolean): Completable {
        return Completable.defer {
            if (shouldRemove) {
                wifiDirectRadioModule.removeGroup()
                    .subscribeOn(computeScheduler)
                    .doOnError { err ->
                        LOG.e("failed to cleanup wifi direct group after termination")
                        firebase.recordException(err)
                    }
            } else {
                Completable.complete()
            }
        }
    }

    private fun awaitAck(clientConnection: CachedLEConnection): Completable {
        return clientConnection.readAck()
            .flatMapCompletable { ack ->
                val message = ack.message ?: "no message"
                val status = ack.status
                if (ack.success)
                    Completable.complete()
                else
                    Completable.error(IllegalStateException("ack failed: $status, $message"))
            }
    }

    private fun sendAck(
        serverConnection: CachedLEServerConnection,
        success: Boolean,
        message: Throwable? = null
    ): Completable {
        val status = if (success) 0 else -1
        return serverConnection.serverNotify(
            AckPacket.newBuilder(success).setMessage(message?.message).setStatus(status).build()
        )
    }

    override fun handleConnection(
        clientConnection: CachedLEConnection,
        device: RxBleDevice,
        luid: UUID
    ): Maybe<HandshakeResult> {
        return Maybe.defer {
                if (state.transactionLock.compareAndSet(null, luid)) {
                    managedGattServer.getServer()
                        .toSingle()
                        .flatMapMaybe { connection ->
                            LOG.v("successfully connected to $luid")
                            val s = LeDeviceSession(
                                device.bluetoothDevice,
                                advertiser.myLuid.get(),
                                clientConnection,
                                connection,
                                luid
                            )
                            LOG.v("initializing session")
                            initializeProtocol(s, TransactionResult.STAGE_LUID)
                                .doOnError { e -> LOG.e("failed to initialize protocol $e") }
                                .flatMapMaybe { session ->
                                    LOG.v("session initialized")
                                    handleStateMachine(
                                        session,
                                        connection,
                                        clientConnection,
                                        luid,
                                        device
                                    )
                                }
                        }
                } else {
                    Maybe.empty()
                }
        }
    }

    /*
     * this is kind of hacky. this observable chain is the driving force of our state machine
     * state transitions are handled when our LeDeviceSession is subscribed to.
     * each transition causes the session to emit the next state and start over
     * since states are only handled on subscribe or on a terminal error, there
     * shouldn't be any races or reentrancy issues
     */
    private fun handleStateMachine(
        session: LeDeviceSession,
        serverConnection: CachedLEServerConnection,
        clientConnection: CachedLEConnection,
        luid: UUID,
        device: RxBleDevice
    ): Maybe<HandshakeResult> {
        return session.observeStage()
            .doOnNext { stage -> LOG.v("handling stage: $stage") }
            .concatMapSingle {
                Single.zip(
                    session.singleClient(),
                    session.singleServer()

                ) { client, server ->
                    val serverResult = server(serverConnection)
                        .onErrorReturn { err -> TransactionResult.err(err) }

                    val clientResult = client(clientConnection)
                        .onErrorReturn { err -> TransactionResult.err(err) }

                    Single.zip(serverResult, clientResult) { s, c -> s.merge(c) }
                }
            }
            .concatMapSingle { s -> s }.concatMapSingle { s -> s }
            .concatMapSingle { res ->
                ackBarrier(serverConnection, clientConnection, res)
            }
            .concatMap { s -> if (s.isError) Observable.error(s.err) else Observable.just(s) }
            .doOnNext { transactionResult ->
                if (session.stage == TransactionResult.STAGE_SUSPEND) {
                    LOG.v("session $luid suspending, no bootstrap")
                } else {
                    val stage = transactionResult.stage ?: TransactionResult.STAGE_TERMINATE
                    session.stage = stage

                }
            }
            .takeUntil { result -> result.stage == TransactionResult.STAGE_TERMINATE }
            .flatMapMaybe { result ->
                if (result.item != null) {
                    LOG.e("boostrapping wifip2p")
                    bootstrapWifiP2p(result.item)
                        .doFinally { session.unlock() }
                        .toMaybe()
                } else {
                    LOG.e("skipping bootstrap")
                    //TODO: record bluetooth LE handshakes
                    session.unlock()
                    Maybe.empty()
                }
            }
            .defaultIfEmpty(
                HandshakeResult(
                    0,
                    0,
                    HandshakeResult.TransactionStatus.STATUS_SUCCESS
                )
            )
            .lastOrError()
            .toMaybe()
            .doOnError { err ->
                LOG.e("session ${session.remoteLuid} ended with error $err")
                err.printStackTrace()
                firebase.recordException(err)
            }
            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
            .doFinally {
                LOG.e("TERMINATION: session $device terminated")
                transactionInProgressRelay.accept(false)
                broadcastReceiverState.dispose()
                if (!state.transactionLock.compareAndSet(luid, null)) {
                    val st = "encountered a love triangle, fwaaaa"
                    LOG.w(st)
                    firebase.recordException(IllegalStateException(st))
                }
            }
    }

    // wait until remote peer sends us an ack, mapping errors to rxjava2 errors
    private fun <T> ackBarrier(
        serverConnection: CachedLEServerConnection,
        clientConnection: CachedLEConnection,
        transactionResult: TransactionResult<T>
    ): Single<TransactionResult<T>> {
        LOG.e("ack barrier: ${transactionResult.stage} ${transactionResult.isError} ${transactionResult.err?.message}")
        val send =
            sendAck(serverConnection, !transactionResult.isError, transactionResult.err)
                .onErrorComplete()
        val await = awaitAck(clientConnection)

        return Completable.mergeArray(
            send,
            await
        ).toSingleDefault(transactionResult)
    }

    /**
     * implent a locking mechanism to grant single devices tempoary
     * exclusive access to channel characteristics
     */
    class LockedCharactersitic(
        @get:Synchronized
        val characteristic: BluetoothGattCharacteristic,
        val channel: Int,
    ) {
        private val lockState = BehaviorRelay.create<Boolean>()
        fun awaitCharacteristic(): Single<OwnedCharacteristic> {
            return Single.just(asUnlocked())
                .zipWith(lockState.filter { p -> !p }.firstOrError()) { ch, _ -> ch }
                .map { ch ->
                    lock()
                    ch
                }
        }

        private fun asUnlocked(): OwnedCharacteristic {
            return OwnedCharacteristic(this)
        }

        val uuid: UUID
            get() = characteristic.uuid

        @Synchronized
        fun lock() {
            lockState.accept(true)
        }

        @Synchronized
        fun release() {
            lockState.accept(false)
        }

        init {
            lockState.accept(false)
        }
    }

    /**
     * represents a characteristic that the caller has exclusive access to
     */
    class OwnedCharacteristic(private val lockedCharactersitic: LockedCharactersitic) {
        private var released = false

        @Synchronized
        fun release() {
            released = true
            lockedCharactersitic.release()
        }

        @get:Synchronized
        @Suppress("Unused")
        val characteristic: BluetoothGattCharacteristic
            get() {
                if (released) {
                    throw ConcurrentModificationException()
                }
                return lockedCharactersitic.characteristic
            }

        val uuid: UUID
            get() = lockedCharactersitic.uuid

    }
}