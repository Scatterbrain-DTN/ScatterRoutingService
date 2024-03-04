package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.subjects.MaybeSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.AckPacket
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val wifiDirectRadioModule: WifiDirectRadioModule,
    private val datastore: ScatterbrainDatastore,
    private val preferences: RouterPreferences,
    private val bootstrapRequestProvider: Provider<BootstrapRequestSubcomponent.Builder>,
    private val firebase: FirebaseWrapper,
    private val state: LeState,
    private val advertiser: Advertiser,
    private val cachedLeServerConnection: CachedLeServerConnection,
    private val connection: CachedLeConnection,
    private val device: RxBleDevice,
    val factory: ScatterbrainTransactionFactory,
    private val currentLuid: UUID,
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_READ) private val bleReadScheduler: Scheduler,
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_WRITE) private val bleWriteScheduler: Scheduler,
    @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.TRANS_IO) private val ioScheduler: Scheduler
) : BluetoothLEModule {
    private val LOG by scatterLog()

    private val sessionCounter = AtomicInteger()

    private val ongoingTransaction: AtomicReference<MaybeSubject<HandshakeResult>> = AtomicReference()

    companion object {
        const val LUID_RANDOMIZE_DELAY = 400

        // scatterbrain service uuid. This is the same for every scatterbrain router.
        val SERVICE_UUID_NEXT: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")
        val SERVICE_UUID_LEGACY: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd91")

        // GATT characteristic uuid for semaphor used for a device to  lock a channel.
        // This is to overcome race conditions caused by the statefulness of the GATT DB
        // we really shouldn't need this but android won't let us have two GATT DBs
        val UUID_SEMAPHOR: UUID = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")

        // characteristic to initiate a session
        val UUID_HELLO: UUID = UUID.fromString("5d1b424e-ff15-49b4-b557-48274634a01a")

        // Characteristic to notify connected peers that we have changed our uuid and they should
        // forget us and disconnect
        val UUID_FORGET: UUID = UUID.fromString("44192A6E-1E9B-442C-B6C3-FAF231DFB808")

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

    private val powerSave: String?
        get() = preferences.getString(
            mContext.getString(R.string.pref_powersave),
            mContext.getString(R.string.powersave_active)
        )

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

    override fun cancelTransaction() {
        val t = ongoingTransaction.getAndSet(null)
        t?.onComplete()
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

                /*
                 * if no one cheats and sending luids, we exchange advertise packets.
                 * This is really boring currently because all scatterbrain routers offer the same capabilities
                 */
                session.addStage(
                    TransactionResult.STAGE_ADVERTISE,
                    { serverConn ->
                        LOG.v("gatt server advertise stage")
                        serverConn.serverNotify(
                            AdvertiseStage.self,
                            session.remoteLuid,
                            session.device
                        )
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

                            LOG.v("gatt server election hashed stage ${provides.name}")

                            val packet = session.votingStage.getSelf(
                                true,
                                provides,
                                wifiDirectRadioModule.getUkes(),
                                session.hashedSelf,
                                if (wifiDirectRadioModule.getForceUke()) ScatterProto.Role.UKE else ScatterProto.Role.SEME,
                                wifiDirectRadioModule.getBand()
                            )
                            session.votingStage.addPacket(packet)
                            serverConn.serverNotify(packet, session.remoteLuid, session.device)
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
                                    val packet = session.votingStage.getSelf(
                                        false,
                                        provides,
                                        wifiDirectRadioModule.getUkes(),
                                        session.hashedSelf,
                                        if (wifiDirectRadioModule.getForceUke()) ScatterProto.Role.UKE else ScatterProto.Role.SEME,
                                        wifiDirectRadioModule.getBand()
                                    )
                                    packet.tagLuid(luidPacket.luidVal)
                                    session.votingStage.addPacket(packet)
                                    serverConn.serverNotify(
                                        packet,
                                        session.remoteLuid,
                                        session.device
                                    )
                                        .doFinally {
                                            session.votingStage.serverPackets.onComplete()
                                        }
                                }
                                    .doOnError { err -> LOG.e("election server error $err") }
                                    .toSingleDefault(TransactionResult.empty())
                            }
                    }
                ) { conn ->
                    LOG.v("gatt client election stage")
                    conn.readElectLeader()
                        .flatMapCompletable { electLeaderPacket ->
                            LOG.v("gatt client received elect leader packet")
                            Observable.fromIterable(electLeaderPacket.force.entries)
                                .flatMapCompletable { (k, v) ->
                                    wifiDirectRadioModule.addUke(k, v)
                                }.andThen(Completable.defer {
                                    session.votingStage.addPacket(electLeaderPacket)
                                    session.votingStage.serverPackets.andThen(session.votingStage.verifyPackets())
                                })
                        }
                        .andThen(session.votingStage.determineUpgrade()
                            .zipWith(wifiDirectRadioModule.wifiDirectIsUsable())
                                { provides, usable ->
                                    LOG.v("election received provides: $provides ${session.luidStage.selfUnhashed}")
                                    val connectionRole = session.votingStage.selectUke()
                                    val ukes = connectionRole.luids
                                    LOG.v("received ukes size ${ukes.size}")
                                    session.role = if (wifiDirectRadioModule.getForceUke() && usable)
                                        ConnectionRole(
                                            role = BluetoothLEModule.Role.ROLE_UKE,
                                            luids = ukes,
                                            band = wifiDirectRadioModule.getBand()
                                        )
                                    else
                                        connectionRole

                                    LOG.v("selected role: ${session.role}")
                                    session.setUpgradeStage(provides)
                                    when (provides.provides) {
                                        AdvertisePacket.Provides.INVALID -> {
                                            LOG.e("received invalid provides")
                                            TransactionResult.err<BootstrapRequest>(
                                                IllegalStateException("invaslid provides")
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
                            .doOnSuccess { result -> LOG.v("client handshake received election result ${result.stage}") })
                }

                /*
                 * if the election state decided we should upgrade, move to a new transport using an upgrade
                 * packet exchange with our newly assigned role.
                 * Currently this just means switch to wifi direct
                 */
                session.addStage(
                    TransactionResult.STAGE_UPGRADE,
                    { serverConn ->
                        LOG.v("gatt server upgrade stage")
                        when (session.role.role) {
                            BluetoothLEModule.Role.ROLE_UKE -> {
                                LOG.e("upgrade role UKE")
                                wifiDirectRadioModule.bootstrapUke(
                                    wifiDirectRadioModule.getBand(),
                                    session.remoteLuid,
                                    advertiser.getHashLuid()
                                ).flatMapCompletable { bootstrapReq ->
                                    LOG.e("uke upgrade callback")
                                    wifiDirectRadioModule.addUke(
                                        advertiser.getHashLuid(), bootstrapReq.toUpgrade(
                                            Random(System.nanoTime()).nextInt()
                                        )
                                    )
                                    serverConn.serverNotify(
                                        bootstrapReq.toUpgrade(session.upgradeStage!!.sessionID),
                                        session.remoteLuid,
                                        session.device
                                    )
                                }
                                    .toSingleDefault(
                                        TransactionResult.of(TransactionResult.STAGE_TERMINATE)
                                    )

                            }

                            BluetoothLEModule.Role.ROLE_SUPERSEME -> {
                                wifiDirectRadioModule.removeUke(advertiser.getHashLuid())
                                Observable.fromIterable(session.role.luids.entries)
                                    .filter { v -> v.key != session.hashedSelf }
                                    .lastElement() //TODO: handle all
                                    .doOnSuccess { v -> LOG.w("awaitUke $v") }
                                    .doOnError { err -> LOG.w("awaitUke timed out $err") }
                                    .flatMapSingle<TransactionResult<BootstrapRequest>> { uke ->
                                        val request = WifiDirectBootstrapRequest.create(
                                            uke.value,
                                            BluetoothLEModule.Role.ROLE_SEME,
                                            bootstrapRequestProvider.get(),
                                            wifiDirectRadioModule.getBand()
                                        )
                                        LOG.v("created request from packet")
                                        serverConn.serverNotify(
                                            uke.value,
                                            session.remoteLuid,
                                            session.device
                                        ).toSingleDefault<TransactionResult<BootstrapRequest>?>(TransactionResult.of(TransactionResult.STAGE_TERMINATE))
                                            .doOnSuccess {
                                                wifiDirectRadioModule.bootstrapSeme(
                                                    request.name,
                                                    request.passphrase,
                                                    wifiDirectRadioModule.getBand(),
                                                    request.port,
                                                    advertiser.getHashLuid()
                                                )
                                            }
                                    }
                            }

                            BluetoothLEModule.Role.ROLE_SEME -> {
                                Single.just(TransactionResult.empty())
                            }
                        }.doFinally { state.votingUnlock() }
                    },
                    { conn ->
                        LOG.v("gatt client upgrade stage")
                        when (session.role.role) {
                            BluetoothLEModule.Role.ROLE_SEME -> {
                                LOG.e("upgrade role SEME")
                                conn.readUpgrade()
                                    .doOnSuccess { p -> LOG.v("client handshake received upgrade packet ${p.metadata.size}") }
                                    .doOnError { err -> LOG.e("error while receiving upgrade packet: $err") }
                                    .flatMap { c ->
                                        wifiDirectRadioModule.addUke(
                                            session.remoteLuid,
                                            c
                                        ).toSingleDefault(c)
                                    }
                                    .flatMap { upgradePacket ->

                                        wifiDirectRadioModule.removeUke(advertiser.getHashLuid())
                                        when (upgradePacket.provides) {
                                            AdvertisePacket.Provides.WIFIP2P -> {
                                                val request = WifiDirectBootstrapRequest.create(
                                                    upgradePacket,
                                                    BluetoothLEModule.Role.ROLE_SEME,
                                                    bootstrapRequestProvider.get(),
                                                    wifiDirectRadioModule.getBand()
                                                )

                                                wifiDirectRadioModule.bootstrapSeme(
                                                    request.name,
                                                    request.passphrase,
                                                    wifiDirectRadioModule.getBand(),
                                                    request.port,
                                                    advertiser.getHashLuid()
                                                )
                                                    Flowable.just(
                                                        TransactionResult.of(
                                                            request as BootstrapRequest,
                                                            TransactionResult.STAGE_TERMINATE,
                                                        )
                                                    )
                                            }

                                            AdvertisePacket.Provides.BLE -> Flowable.just(
                                                TransactionResult.of(
                                                    TransactionResult.STAGE_IDENTITY
                                                )
                                            )

                                            else -> Flowable.just(
                                                TransactionResult.err(
                                                    IllegalStateException("invalid provides ${upgradePacket.provides}")
                                                )
                                            )
                                        }.reduce<TransactionResult<BootstrapRequest>?>(
                                            TransactionResult.of(TransactionResult.STAGE_TERMINATE)
                                        ) { first, second ->
                                            if (first.isError) {
                                                first
                                            } else {
                                                second
                                            }
                                        }
                                            .doOnSuccess { v -> LOG.w("seme terminating with stage ${v.stage}") }
                                            .flatMap { v ->
                                                if (v.isError) {
                                                    Single.error(v.err!!)
                                                } else {
                                                    Single.just(
                                                        TransactionResult.of(
                                                            TransactionResult.STAGE_TERMINATE
                                                        )
                                                    )
                                                }
                                            }
                                    }
                            }

                            else -> {
                                Single.just(TransactionResult.empty())
                            }
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
                            .concatMapCompletable { packet ->
                                serverConn.serverNotify(
                                    packet,
                                    session.remoteLuid,
                                    session.device
                                )
                            }
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
                                serverConn.serverNotify(packet, session.remoteLuid, session.device)
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
                                        serverConn.serverNotify(
                                            message.headerPacket,
                                            session.remoteLuid,
                                            session.device
                                        )
                                            .andThen(message.sequencePackets.concatMapCompletable { packet ->
                                                serverConn.serverNotify(
                                                    packet,
                                                    session.remoteLuid,
                                                    session.device
                                                )
                                            })
                                    }
                            }.toSingleDefault(TransactionResult.empty())
                    },
                    { conn ->
                        conn.readBlockHeader()
                            .flatMap { blockHeaderPacket ->
                                LOG.v("header ${blockHeaderPacket.hashList.size}")
                                if (blockHeaderPacket.hashList.isNotEmpty()) {
                                    val m = BlockDataStream(
                                        blockHeaderPacket,
                                        conn.readBlockSequence()
                                            .repeat()
                                            .doOnNext { v -> LOG.w("ble sequence packet ${v.data.size} ${v.isEnd}") }
                                            .takeWhile { p -> !p.isEnd },
                                        datastore.cacheDir
                                    )

                                    datastore.insertMessage(m).andThen(m.await())
                                        .toSingleDefault(blockHeaderPacket)
                                        .doOnSuccess { LOG.w("BLE insert complete") }
                                } else {
                                    Single.just(blockHeaderPacket)
                                }

                            }
                            .repeat()
                            .takeWhile { stream ->
                                val end = stream.isEndOfStream
                                if (end) {
                                    LOG.v("uke end of stream")
                                }
                                !end
                            }
                            .ignoreElements()
                            .toSingleDefault(TransactionResult.of(TransactionResult.STAGE_TERMINATE))
                    })

                // set our starting stage
                session.stage = defaultState
                session
            }
    }

    override fun initiateOutgoingConnection(
        luid: UUID
    ): Maybe<HandshakeResult> {
        return connection.connection
            .firstOrError()
            .flatMapMaybe { serverConnection ->
                LOG.e("initiateOutgoingConnection luid $luid")
                val hash = advertiser.getHashLuid()
                LOG.v("writing hashed luid $hash")
                serverConnection.writeCharacteristic(UUID_HELLO, uuid2bytes(hash)!!)
                    .doOnSuccess { res ->
                        LOG.v("successfully wrote uuid len ${res.size}")
                    }
                    .doOnError { e ->
                        LOG.e("failed to write characteristic: $e. This is probably just a lock")
                        state.updateGone(luid)
                    }
                    .ignoreElement()
                    .andThen(
                        handleConnection(luid)
                    )
                    .onErrorComplete()
            }
            .doOnError { err ->
                LOG.v("error in initiateOutgoingConnection $err")
                firebase.recordException(err)
                //state.updateDisconnected(luid)
            }
    }

    /**
     * kill current scan in progress
     */
    override fun stopDiscover() {
    }

    private fun awaitAck(clientConnection: CachedLeConnection): Completable {
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
        serverConnection: CachedLeServerConnection,
        success: Boolean,
        luid: UUID,
        device: RxBleDevice,
        message: Throwable? = null
    ): Completable {
        val status = if (success) 0 else -1
        return serverConnection.serverNotify(
            AckPacket.newBuilder(success).setMessage(message?.message).setStatus(status).build(),
            luid,
            device
        )
    }

    override fun isBusy(): Boolean {
        return ongoingTransaction.get() != null
    }

    override fun handleConnection(
        luid: UUID
    ): Maybe<HandshakeResult> {
        return Maybe.defer {
            advertiser.setRandomizeTimer(10)
            if (luid == currentLuid) {
                ongoingTransaction.updateAndGet { v ->
                    when (v) {
                        null -> {
                            LOG.w("NEW transaction for $luid")
                            val subject = MaybeSubject.create<HandshakeResult>()
                            val obs = Single.just(cachedLeServerConnection)
                                .flatMapMaybe { serverConnection ->
                                    val t = state.startTransaction()
                                    LOG.v("successfully connected to $luid, transactions: $t")
                                    val s = LeDeviceSession(
                                        device,
                                        advertiser.getRawLuid(),
                                        connection,
                                        serverConnection,
                                        luid,
                                        advertiser.getHashLuid()
                                    )
                                    val count = sessionCounter.incrementAndGet()
                                    LOG.v("initializing session $count")
                                    initializeProtocol(s, TransactionResult.STAGE_ADVERTISE)
                                        .doOnError { e -> LOG.e("failed to initialize protocol $e") }
                                        .flatMapMaybe { session ->
                                            LOG.v("session initialized")
                                            handleStateMachine(
                                                session,
                                                serverConnection,
                                                connection,
                                                luid,
                                                device
                                            )
                                        }
                                        .doFinally { sessionCounter.decrementAndGet() }
                                }
                                .doOnDispose {
                                    val t = state.stopTransaction()
                                    LOG.e("transaction disposed, $t")
                                    ongoingTransaction.set(null)
                                }
                                .doOnError { e ->
                                    LOG.e("transaction error")
                                    firebase.recordException(e)
                                    e.printStackTrace()
                                    //state.updateDisconnected(luid)
                                }
                                .doFinally {
                                    val t = state.stopTransaction()
                                    LOG.w("transaction completed, $t remaining")
                                    ongoingTransaction.set(null)
                                }
                            obs.subscribe(subject)
                            subject
                        }

                        else -> {
                            LOG.w("cached transaction for $luid")
                            v
                        }
                    }
                }
            } else {
                LOG.w("invalid luid when processing transaction: required = $currentLuid asked = $luid")
                Maybe.empty()
            }
        }.doOnError { err ->
            LOG.w("handle connection error: $err, dumping peers")
            state.updateGone(luid)
            state.updateDisconnected(luid)
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
        serverConnection: CachedLeServerConnection,
        clientConnection: CachedLeConnection,
        luid: UUID,
        device: RxBleDevice
    ): Maybe<HandshakeResult> {
        return connection.subscribeNotifs()
            .andThen(connection.connection.firstOrError().flatMapCompletable { c -> c.requestMtu(512).ignoreElement() })
            .andThen(session.observeStage())
            .doOnNext { stage -> LOG.v("handling stage: $stage") }
            .concatMapSingle {
                Single.zip(
                    session.singleClient(),
                    session.singleServer()
                ) { client, server ->
                    val serverResult = server(serverConnection)
                        .subscribeOn(bleWriteScheduler)
                        .doOnError { err -> LOG.e("server error $err") }
                        .onErrorReturn { err -> TransactionResult.err(err) }

                    val clientResult = client(clientConnection)
                        .subscribeOn(bleReadScheduler)
                        .doOnError { err -> LOG.e("client error $err") }
                        .onErrorReturn { err -> TransactionResult.err(err) }

                    Single.zip(serverResult, clientResult) { s, c ->
                        s.merge(c)
                    }.flatMapMaybe { v ->  v  }
                }
                    .flatMapMaybe { v -> v }
                    .toSingle(TransactionResult.err(IllegalStateException("empty transaction merge")))
                    .flatMap { v -> ackBarrier(serverConnection, clientConnection, v, luid, device) }

            }
            .doOnNext { transactionResult ->
                val stage = transactionResult.stage ?: TransactionResult.STAGE_TERMINATE
                session.stage = stage
            }
            .doOnNext { r ->
                LOG.w("session unlocked for stage ${r.stage}")
                session.unlock()
            }
            .flatMap { s -> if (s.isError) Observable.error(s.err) else Observable.just(s) }
            .takeUntil { result -> result.stage == TransactionResult.STAGE_TERMINATE }
            .ignoreElements()
            .toSingleDefault(
                HandshakeResult(
                    0,
                    0,
                    HandshakeResult.TransactionStatus.STATUS_SUCCESS
                )
            )
            .toMaybe()
            .doOnError { err ->
                LOG.e("session ${session.remoteLuid} ended with error $err")
                firebase.recordException(err)
                state.updateGone(luid)
                state.updateDisconnected(luid)
            }
            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
            .doFinally {
                LOG.e("TERMINATION: session $device terminated")
                if(session.role.role != BluetoothLEModule.Role.ROLE_UKE) {
                 //   state.updateDisconnected(luid)
                }
                serverConnection.unlockLuid(luid)
            }
    }

    // wait until remote peer sends us an ack, mapping errors to rxjava2 errors
    private fun <T> ackBarrier(
        serverConnection: CachedLeServerConnection,
        clientConnection: CachedLeConnection,
        transactionResult: TransactionResult<T>,
        luid: UUID,
        device: RxBleDevice
    ): Single<TransactionResult<T>> {
        return Single.defer {
            LOG.e("ack barrier: ${transactionResult.stage} ${transactionResult.isError} ${transactionResult.err?.message}")
            val send =
                sendAck(
                    serverConnection,
                    !transactionResult.isError,
                    luid,
                    device,
                    transactionResult.err
                )
                    .onErrorComplete()
            val await = awaitAck(clientConnection)

            send.andThen(await)
                .toSingleDefault(transactionResult)
        }
    }

    /**
     * implent a locking mechanism to grant single devices tempoary
     * exclusive access to channel characteristics
     */
    class LockedCharacteristic(
        val characteristic: BluetoothGattCharacteristic,
        val channel: Int,
    ) {
        private val lockState = AtomicBoolean()
        private fun asUnlocked(): OwnedCharacteristic {
            return OwnedCharacteristic(this)
        }

        val uuid: UUID
            get() = characteristic.uuid

        fun lock(): OwnedCharacteristic? {
            val lock = lockState.getAndSet(true)
            return if (!lock) {
                asUnlocked()
            } else {
                null
            }
        }

        fun isLocked(): Boolean {
            return lockState.get()
        }

        fun release() {
            Log.v("debug", "selected channel release: ${characteristic.uuid}")
            lockState.set(false)
        }
    }

    /**
     * represents a characteristic that the caller has exclusive access to
     */
    class OwnedCharacteristic(private val lockedCharactersitic: LockedCharacteristic) {
        private var released = false

        fun release() {
            released = true
            lockedCharactersitic.release()
        }

        fun isLocked(): Boolean {
            return !released
        }

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