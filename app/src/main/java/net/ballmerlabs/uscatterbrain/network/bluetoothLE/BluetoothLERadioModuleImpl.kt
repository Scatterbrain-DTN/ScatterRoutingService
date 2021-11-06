package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.*
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.BuildConfig
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.hashAsUUID
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule.BlockDataStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class OptionalBootstrap<T>(
        val item: T? = null,
) {
    val isPresent: Boolean
        get() = item != null

    companion object {
        fun <T> of(v: T): OptionalBootstrap<T> {
            return OptionalBootstrap(v)
        }

        fun <T> empty(): OptionalBootstrap<T> {
            return OptionalBootstrap(null)
        }
    }
}


/**
 * Bluetooth low energy transport implementation.
 * This transport provides devices discovery, data transfer,
 * and bootstrapping to other transports.
 *
 * Logic is implemented as a finite state machine to simplify
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
@Singleton
class BluetoothLERadioModuleImpl @Inject constructor(
        private val mContext: Context,
        private val mAdvertiser: BluetoothLeAdvertiser,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) private val clientScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER) private val serverScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.OPERATIONS) private val operationsScheduler: Scheduler,
        private val mClient: RxBleClient,
        private val wifiDirectRadioModule: WifiDirectRadioModule,
        private val datastore: ScatterbrainDatastore,
        powerManager: PowerManager,
        private val preferences: RouterPreferences,
) : BluetoothLEModule {
    private val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            mContext.getString(R.string.wakelock_tag
            ))

    private val serverStarted = AtomicReference(false)

    companion object {
        const val TAG = "BluetoothLE"
        const val CLIENT_CONNECT_TIMEOUT = 10

        // scatterbrain service uuid. This is the same for every scatterbrain router.
        val SERVICE_UUID: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")

        // GATT characteristic uuid for semaphor used for a device to  lock a channel.
        // This is to overcome race conditions caused by the statefulness of the GATT DB
        // we really shouldn't need this but android won't let us have two GATT DBs
        val UUID_SEMAPHOR: UUID = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")

        // characteristic to initiate a session
        val UUID_HELLO: UUID = UUID.fromString("5d1b424e-ff15-49b4-b557-48274634a01a")

        // a "channel" is a characteristc that protobuf messages are written to.
        val channels = ConcurrentHashMap<UUID, LockedCharactersitic>()

        // number of channels. This can be increased or decreased for performance
        private const val NUM_CHANNELS = 8

        // scatterbrain gatt service object
        val mService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        //avoid triggering concurrent peer refreshes
        val refreshInProgresss = BehaviorRelay.create<Boolean>()

        val discoveryPersistent = AtomicReference(false)

        private val serverSubject = SingleSubject.create<CachedLEServerConnection>()

        private fun incrementUUID(uuid: UUID, i: Int): UUID {
            val buffer = ByteBuffer.allocate(16)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            val b = BigInteger(buffer.array()).add(BigInteger.valueOf(i.toLong()))
            val out = ByteBuffer.wrap(b.toByteArray())
            val high = out.long
            val low = out.long
            return UUID(high, low)
        }

        fun uuid2bytes(uuid: UUID?): ByteArray {
            val buffer = ByteBuffer.allocate(16)
            buffer.putLong(uuid!!.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            return buffer.array()
        }

        fun bytes2uuid(bytes: ByteArray): UUID {
            val buffer = ByteBuffer.wrap(bytes)
            val high = buffer.long
            val low = buffer.long
            return UUID(high, low)
        }

        /*
         * shortcut to generate a characteristic with the required permissions
         * and properties and add it to our service.
         * We need PROPERTY_INDICATE to send large protobuf blobs and
         * READ and WRITE for timing / locking
         */
        private fun makeCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
            val characteristic = BluetoothGattCharacteristic(
                    uuid,
                    BluetoothGattCharacteristic.PROPERTY_READ or
                            BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE or
                            BluetoothGattCharacteristic.PERMISSION_READ
            )
            mService.addCharacteristic(characteristic)
            return characteristic
        }

        init {
            // initialize our channels
            makeCharacteristic(UUID_SEMAPHOR)
            makeCharacteristic(UUID_HELLO)
            for (i in 0 until NUM_CHANNELS) {
                val channel = incrementUUID(SERVICE_UUID, i + 1)
                channels[channel] = LockedCharactersitic(makeCharacteristic(channel), i)
            }
            refreshInProgresss.accept(false)
        }
    }

    private val mGattDisposable = CompositeDisposable()
    private val discoveryDispoable = AtomicReference<Disposable>()

    //we need to cache connections because RxAndroidBle doesn't like making double GATT connections
    private val connectionCache = ConcurrentHashMap<String, CachedLEConnection>()

    private val transactionCompleteRelay = PublishRelay.create<HandshakeResult>()

    // luid is a temporary unique identifier used for a single transaction.
    private val myLuid = AtomicReference(UUID.randomUUID())

    private val sessionCache = ConcurrentHashMap<UUID, LeDeviceSession>()
    private val activeLuids = ConcurrentHashMap<UUID, Boolean>()
    private val transactionErrorRelay = PublishRelay.create<Throwable>()

    private val mAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.v(TAG, "successfully started advertise")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "failed to start advertise")
        }
    }

    private fun getLuidClient(conn: CachedLEConnection): Single<LuidPacket> {
        return conn.readLuid()
                .doOnError { err ->
                    Log.e(TAG, "error while receiving luid packet: $err")
                    err.printStackTrace()
                }
                .subscribeOn(clientScheduler)
                .flatMap { luidPacket ->
                    if (luidPacket.protoVersion != ScatterRoutingService.PROTO_VERSION) {
                        Log.e(TAG, "error, device connected with invalid protocol version: " + luidPacket.protoVersion)
                        return@flatMap Single.error(java.lang.IllegalStateException("invalid proto version"))
                    }

                    val hashUUID = luidPacket.hashAsUUID!!
                    Log.e("debug", "version: ${luidPacket.protoVersion} hash ${luidPacket.hashAsUUID}")
                    if (activeLuids.contains(hashUUID)) {
                        Log.e(TAG, "device already active")

                        return@flatMap Single.error(IllegalStateException("device already connected"))
                    } else {
                        //TODO:
                       // activeLuids.add(hashUUID)
                    }

                    Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.valCase)
                    Single.just(luidPacket)
                }
    }

    private fun getLuidServer(serverConn: CachedLEServerConnection): Completable {
        return Single.fromCallable {
            LuidPacket.newBuilder()
                    .enableHashing(ScatterRoutingService.PROTO_VERSION)
                    .setLuid(myLuid.get())
                    .build()
        }
                .flatMapCompletable { luidpacket ->
                    serverConn.serverNotify(luidpacket)
                }
                .subscribeOn(serverScheduler)
    }

    private fun observeTransactionComplete() {
        val d = transactionCompleteRelay.subscribe(
                {
                    Log.v(TAG, "transaction complete, randomizing luid")
                    releaseWakeLock()
                }
        ) { err ->
            Log.e(TAG, "error in transactionCompleteRelay $err")
            releaseWakeLock()
        }
        mGattDisposable.add(d)
    }

    /*
     * we should always hold a wakelock directly after the adapter wakes up the device
     * when a scatterbrain uuid is detected. The adapter is responsible for waking up the device
     * via offloaded scanning, but NOT for keeping it awake.
     */
    private fun acquireWakelock() {
        wakeLock.acquire((10 * 60 * 1000).toLong())
    }

    private fun releaseWakeLock() {
        wakeLock.release()
    }

    private val powerSave: String?
        get() = preferences.getString(
                mContext.getString(R.string.pref_powersave),
                mContext.getString(R.string.powersave_active)
        )

    private fun parseScanMode(): Int {
        return when (powerSave) {
            mContext.getString(R.string.powersave_active) -> {
                ScanSettings.SCAN_MODE_LOW_POWER
            }
            mContext.getString(R.string.powersave_opportunistic) -> {
                ScanSettings.SCAN_MODE_OPPORTUNISTIC
            }
            else -> {
                -1 //scan disabled
            }
        }
    }

    /**
     * start offloaded advertising. This should continue even after the phone is asleep
     */
    override fun startAdvertise() {
        Log.v(TAG, "Starting LE advertise")
        val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
        val addata = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        mAdvertiser.startAdvertising(settings, addata, mAdvertiseCallback)
    }

    /**
     * stop offloaded advertising
     */
    override fun stopAdvertise() {
        Log.v(TAG, "stopping LE advertise")
        mAdvertiser.stopAdvertising(mAdvertiseCallback)
    }

    private fun selectProvides(): AdvertisePacket.Provides {
        return if (preferences.getBoolean(mContext.getString(R.string.pref_incognito), false)) AdvertisePacket.Provides.BLE else AdvertisePacket.Provides.WIFIP2P
    }

    /*
     * initialize the finite state machine. This is VERY important and dictates the
     * bluetooth LE transport's behavior for the entire protocol.
     * the LeDeviceSession object holds the stages and stage transition logic, and is
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
        Log.v(TAG, "initialize protocol")
        return Single.just(s)
                .map { session ->
                    /*
                     * luid stage reveals unhashed packets after all hashed packets are collected
                     * hashes are compared to unhashed packets
                     */
                    session.addStage(
                            TransactionResult.STAGE_LUID,
                            { serverConn ->
                                Log.v(TAG, "gatt server luid stage")
                                serverConn.serverNotify(session.luidStage.selfUnhashedPacket)
                                        .doOnError { err -> Log.e(TAG, "luid server failed $err") }
                                        .toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                Log.v(TAG, "gatt client luid stage")
                                conn.readLuid()
                                        .doOnSuccess { luidPacket ->
                                            Log.v(TAG, "client handshake received unhashed luid packet: " + luidPacket.luidVal)
                                            session.luidStage.setPacket(luidPacket)
                                        }
                                        .doOnError { err ->
                                            Log.e(TAG, "error while receiving luid packet: $err")
                                            err.printStackTrace()
                                        }
                                        .flatMapCompletable { luidPacket ->
                                            session.luidStage.verifyPackets()
                                                    .doOnComplete {
                                                        Log.v(TAG, "successfully verified luid packet")
                                                        session.luidMap[session.device.address] = luidPacket.luidVal
                                                    } //TODO: stop this
                                        }
                                        .toSingleDefault(TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ADVERTISE, session.device, session.luidStage.remoteHashed))
                                        .doOnError { err ->
                                            Log.e(TAG, "luid hash verify failed: $err")
                                            err.printStackTrace()
                                        }
                                        .onErrorReturnItem(TransactionResult(TransactionResult.STAGE_SUSPEND, session.device, session.luidStage.remoteHashed))
                            })

                    /*
                     * if no one cheats and sending luids, we exchange advertise packets.
                     * This is really boring currently because all scatterbrain routers offer the same capabilities
                     */
                    session.addStage(
                            TransactionResult.STAGE_ADVERTISE,
                            { serverConn ->
                                Log.v(TAG, "gatt server advertise stage")
                                serverConn.serverNotify(AdvertiseStage.self)
                                        .toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                Log.v(TAG, "gatt client advertise stage")
                                conn.readAdvertise()
                                        .doOnSuccess { Log.v(TAG, "client handshake received advertise packet") }
                                        .doOnError { err -> Log.e(TAG, "error while receiving advertise packet: $err") }
                                        .map { advertisePacket ->
                                            session.advertiseStage.addPacket(advertisePacket)
                                            TransactionResult(TransactionResult.STAGE_ELECTION_HASHED, session.device, session.luidStage.remoteHashed)
                                        }
                            })

                    /*
                     * the leader election state is really complex, it is a good idea to check the documentation in
                     * VotingStage.kt to figure out how this works.
                     */
                    session.addStage(
                            TransactionResult.STAGE_ELECTION_HASHED,
                            { serverConn ->
                                Log.v(TAG, "gatt server election hashed stage")
                                val packet = session.votingStage.getSelf(true, selectProvides())
                                if (BuildConfig.DEBUG && !packet.isHashed) {
                                    error("Assertion failed")
                                }
                                session.votingStage.addPacket(packet)
                                serverConn.serverNotify(packet)
                                        .toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                Log.v(TAG, "gatt client election hashed stage")
                                conn.readElectLeader()
                                        .doOnSuccess { Log.v(TAG, "client handshake received hashed election packet") }
                                        .doOnError { err -> Log.e(TAG, "error while receiving election packet: $err") }
                                        .map { electLeaderPacket ->
                                            session.votingStage.addPacket(electLeaderPacket)
                                            TransactionResult(TransactionResult.STAGE_ELECTION, session.device, session.luidStage.remoteHashed)
                                        }
                            })

                    /*
                     * see above
                     * Once the election stage is finished we move on to the actions decided by the result of the election
                     */
                    session.addStage(
                            TransactionResult.STAGE_ELECTION,
                            { serverConn ->
                                Log.v(TAG, "gatt server election stage")
                                Single.just(session.luidStage.selfUnhashedPacket)
                                        .flatMapCompletable { luidPacket ->
                                            if (BuildConfig.DEBUG && luidPacket.isHashed) {
                                                error("Assertion failed")
                                            }
                                            val packet = session.votingStage.getSelf(false, selectProvides())
                                            packet.tagLuid(luidPacket.luidVal)
                                            session.votingStage.addPacket(packet)
                                            serverConn.serverNotify(packet)
                                        }.toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                Log.v(TAG, "gatt client election stage")
                                conn.readElectLeader()
                                        .flatMapCompletable { electLeaderPacket ->
                                            electLeaderPacket.tagLuid(session.luidMap[session.device.address])
                                            session.votingStage.addPacket(electLeaderPacket)
                                            session.votingStage.verifyPackets()
                                        }
                                        .andThen(session.votingStage.determineUpgrade())
                                        .map { provides ->
                                            Log.v(TAG, "election received provides: $provides")
                                            val role: ConnectionRole = if (session.votingStage.selectSeme() == session.luidStage.selfUnhashed) {
                                                ConnectionRole.ROLE_SEME
                                            } else {
                                                ConnectionRole.ROLE_UKE
                                            }
                                            Log.v(TAG, "selected role: $role")
                                            session.role = role
                                            session.setUpgradeStage(provides)
                                            when (provides) {
                                                AdvertisePacket.Provides.INVALID -> {
                                                    Log.e(TAG, "received invalid provides")
                                                    TransactionResult<BootstrapRequest>(
                                                            TransactionResult.STAGE_SUSPEND,
                                                            session.device,
                                                            session.luidStage.remoteHashed
                                                    )
                                                }
                                                AdvertisePacket.Provides.BLE -> {
                                                    //we should do everything in BLE. slowwwww ;(
                                                    TransactionResult(
                                                            TransactionResult.STAGE_IDENTITY,
                                                            session.device,
                                                            session.luidStage.remoteHashed
                                                    )
                                                }
                                                AdvertisePacket.Provides.WIFIP2P ->
                                                    TransactionResult(
                                                            TransactionResult.STAGE_UPGRADE,
                                                            session.device,
                                                            session.luidStage.remoteHashed
                                                    )
                                            }
                                        }
                                        .doOnError { err -> Log.e(TAG, "error while receiving packet: $err") }
                                        .doOnSuccess { result -> Log.v(TAG, "client handshake received election result ${result.nextStage}") }
                                        .onErrorReturn {
                                            TransactionResult(TransactionResult.STAGE_SUSPEND, session.device, session.luidStage.remoteHashed)
                                        }
                            })

                    /*
                     * if the election state decided we should upgrade, move to a new transport using an upgrade
                     * packet exchange with our newly assigned role.
                     * Currently this just means switch to wifi direct
                     */
                    session.addStage(
                            TransactionResult.STAGE_UPGRADE,
                            { serverConn ->
                                Log.v(TAG, "gatt server upgrade stage")
                                if (session.role == ConnectionRole.ROLE_UKE) {
                                    wifiDirectRadioModule.createGroup()
                                            .flatMap { bootstrap ->
                                                val upgradePacket = bootstrap.toUpgrade(session.upgradeStage!!.sessionID)
                                                serverConn.serverNotify(upgradePacket)
                                                        .toSingleDefault(OptionalBootstrap.of(bootstrap))
                                            }
                                } else {
                                    Single.just(OptionalBootstrap.empty())
                                }
                            },
                            { conn ->
                                Log.v(TAG, "gatt client upgrade stage")
                                if (session.role == ConnectionRole.ROLE_SEME) {
                                    conn.readUpgrade()
                                            .doOnSuccess { Log.v(TAG, "client handshake received upgrade packet") }
                                            .doOnError { err -> Log.e(TAG, "error while receiving upgrade packet: $err") }
                                            .map { upgradePacket ->
                                                if (upgradePacket.provides == AdvertisePacket.Provides.WIFIP2P) {
                                                    val request = WifiDirectBootstrapRequest.create(upgradePacket, ConnectionRole.ROLE_SEME)
                                                    TransactionResult(
                                                            TransactionResult.STAGE_SUSPEND,
                                                            session.device,
                                                            session.luidStage.remoteHashed,
                                                            result = request
                                                    )
                                                } else {
                                                    TransactionResult(
                                                            TransactionResult.STAGE_TERMINATE,
                                                            session.device,
                                                            session.luidStage.remoteHashed,
                                                            err = true
                                                    )
                                                }
                                            }
                                } else {
                                    Single.just(TransactionResult<BootstrapRequest>(
                                            TransactionResult.STAGE_SUSPEND,
                                            session.device,
                                            session.luidStage.remoteHashed)
                                    )
                                }
                            })

                    /*
                     * if we chose not to upgrade, proceed with exchanging identity packets
                     */
                    session.addStage(
                            TransactionResult.STAGE_IDENTITY,
                            { serverConn ->
                                datastore.getTopRandomIdentities(
                                        preferences.getInt(mContext.getString(R.string.pref_identitycap), 32)
                                )
                                        .concatMapCompletable { packet -> serverConn.serverNotify(packet) }
                                        .toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                conn.readIdentityPacket()
                                        .repeat()
                                        .takeWhile { identityPacket ->
                                            val end = !identityPacket.isEnd
                                            if (!end) {
                                                Log.v(TAG, "identitypacket end of stream")
                                            }
                                            end
                                        }
                                        .ignoreElements()
                                        .toSingleDefault(
                                                TransactionResult(
                                                        TransactionResult.STAGE_DECLARE_HASHES,
                                                        session.device,
                                                        session.luidStage.remoteHashed
                                                )
                                        )
                            })

                    /*
                     * if we chose not to upgrade, exchange delcare hashes packets
                     */
                    session.addStage(
                            TransactionResult.STAGE_DECLARE_HASHES,
                            { serverConn ->
                                Log.v(TAG, "gatt server declareHashes stage")
                                datastore.declareHashesPacket
                                        .flatMapCompletable { packet -> serverConn.serverNotify(packet) }
                                        .toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                conn.readDeclareHashes()
                                        .doOnSuccess { declareHashesPacket ->
                                            Log.v(TAG, "client handshake received declareHashes: " +
                                                    declareHashesPacket.hashes.size)
                                        }
                                        .doOnError { err -> Log.e(TAG, "error while receiving declareHashes packet: $err") }
                                        .map { declareHashesPacket ->
                                            session.setDeclareHashesPacket(declareHashesPacket)
                                            TransactionResult(TransactionResult.STAGE_BLOCKDATA, session.device, session.luidStage.remoteHashed)
                                        }
                            })

                    /*
                     * if we chose not to upgrade, exchange messages V E R Y S L O W LY
                     * TODO: put some sort of cap on file size to avoid hanging here for hours
                     */
                    session.addStage(
                            TransactionResult.STAGE_BLOCKDATA,
                            { serverConn ->
                                Log.v(TAG, "gatt server blockdata stage")
                                session.declareHashes
                                        .flatMapCompletable { declareHashesPacket ->
                                            datastore.getTopRandomMessages(
                                                    preferences.getInt(mContext.getString(R.string.pref_blockdatacap), 30),
                                                    declareHashesPacket
                                            )
                                                    .concatMapCompletable { message ->
                                                        serverConn.serverNotify(message.headerPacket)
                                                                .andThen(message.sequencePackets.concatMapCompletable { packet ->
                                                                    serverConn.serverNotify(packet)
                                                                })
                                                    }
                                        }.toSingleDefault(OptionalBootstrap.empty())
                            },
                            { conn ->
                                conn.readBlockHeader()
                                        .toFlowable()
                                        .flatMap { blockHeaderPacket ->
                                            Flowable.range(0, blockHeaderPacket.hashList.size)
                                                    .map {
                                                        BlockDataStream(
                                                                blockHeaderPacket,
                                                                conn.readBlockSequence()
                                                                        .repeat(blockHeaderPacket.hashList.size.toLong())
                                                        )
                                                    }
                                        }
                                        .takeUntil { stream ->
                                            val end = stream.headerPacket.isEndOfStream
                                            if (end) {
                                                Log.v(TAG, "uke end of stream")
                                            }
                                            end
                                        }
                                        .concatMapSingle { m -> datastore.insertMessage(m).andThen(m.await()).toSingleDefault(0) }
                                        .reduce { a, b -> a + b }
                                        .toSingle()
                                        .map { i -> HandshakeResult(0, i, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                                        .map { res ->
                                            transactionCompleteRelay.accept(res)
                                            TransactionResult(TransactionResult.STAGE_SUSPEND, session.device, session.luidStage.remoteHashed)
                                        }
                            })

                    // set our starting stage
                    session.stage = defaultState
                    session
                }
    }

    private fun removeConnection(device: String) {
        try {
            val disp = connectionCache.remove(device)
            disp?.dispose()
        } catch (exception: NoSuchElementException) {
            Log.w(TAG, "tried to remove nonexistent connection $device")
        }
    }

    /* attempt to bootstrap to wifi direct using upgrade packet (gatt client version) */
    private fun bootstrapWifiP2p(bootstrapRequest: BootstrapRequest): Single<HandshakeResult> {
        return wifiDirectRadioModule.bootstrapFromUpgrade(bootstrapRequest)
                .doOnError { err ->
                    Log.e(TAG, "wifi p2p upgrade failed: $err")
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

    override fun refreshPeers(): Completable {
        Log.v(TAG, "refreshing ${sessionCache.size} peers")
        return refreshInProgresss
                .firstOrError()
                .flatMapCompletable { b ->
                    if (b) refreshInProgresss.takeUntil { v -> !v }
                            .ignoreElements()
                    else {
                        Observable.fromIterable(sessionCache.values)
                                .concatMapCompletable { session ->
                                    Single.just(session.client.connection)
                                            .flatMap { connection ->
                                                connection.writeCharacteristic(UUID_SEMAPHOR, uuid2bytes(
                                                        LuidPacket.newBuilder()
                                                                .setLuid(myLuid.get())
                                                                .enableHashing(ScatterRoutingService.PROTO_VERSION)
                                                                .build().hashAsUUID!!
                                                ))
                                            }
                                            .flatMapCompletable {
                                                Log.v(TAG, "received success code, rewinding session")
                                                rewindSession(session)
                                                awaitTransaction()
                                            }
                                            .doOnError { err -> Log.e(TAG, "remote peer busy, ignoring for now: $err") }
                                            .onErrorComplete()
                                }
                    }
                }
                .doOnError { err ->
                    Log.e(TAG, "error on refresh $err")
                    err.printStackTrace()
                    refreshInProgresss.accept(false)
                }
                .doOnComplete { refreshInProgresss.accept(false) }

    }

    /*
     * discover LE devices. Currently this runs forever and sets a global disposable
     * so we can kill it if we want.
     * This only scans for scatterbrain UUIDs
     * TODO: make sure this runs on correct scheduler
     */
    private fun discoverOnce(forever: Boolean): Disposable {
        Log.d(TAG, "discover once called")
        val d = CompositeDisposable()
        val disp = discoveryDispoable.getAndSet(d)
        disp?.dispose()
        val newd = mClient.scanBleDevices(
                ScanSettings.Builder()
                        .setScanMode(parseScanMode())
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setShouldCheckLocationServicesState(true)
                        .build(),
                ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build())
                .flatMap { scanResult ->
                    acquireWakelock()
                    Log.d(TAG, "scan result: " + scanResult.bleDevice.macAddress)
                    scanResult.bleDevice.establishConnection(false)
                        .flatMapSingle { connection ->
                            Log.v(TAG, "established connection to ${scanResult.bleDevice.macAddress}")
                            val hash = uuid2bytes(hashAsUUID(LuidPacket.calculateHashFromUUID(myLuid.get())))
                            Log.e(TAG, "writing hash len ${hash.size}")
                            connection.discoverServices()
                                .flatMap { serv ->
                                    serv.getCharacteristic(UUID_HELLO)
                                        .flatMap { char ->
                                            Log.v(TAG, "found hello characteristic: ${char.uuid}")
                                            connection.writeCharacteristic(char, hash)
                                                .doOnSuccess { v -> Log.v(TAG, "successfully wrote uuid len ${v.size}") }
                                                .doOnError{ e -> Log.e(TAG, "failed to write characteristic: $e")}
                                                .ignoreElement()
                                                .toSingleDefault(connection)
                                        }
                                }
                        }
                        .concatMap { connection ->
                            handleConnection(Observable.just(connection), scanResult.bleDevice)
                        }
                    /*
                     * WAIT? why are we not doing anything with our gatt connections??
                     * see the comment in startServer() for why
                     */

                }
                .ignoreElements()
                .repeat()
                .retry()
                .doOnError { err -> Log.e(TAG, "error with initial handshake: $err") }
                .subscribe(
                        { Log.v(TAG, "handshake completed") },
                        { err ->
                            Log.e(TAG, """
                                handshake failed: $err
                                ${Arrays.toString(err.stackTrace)}
                                """.trimIndent())
                        }
                )
        if (!forever) {
            val timeoutDisp = Completable.fromAction {}
                    .delay(
                            preferences.getLong(
                                    mContext.getString(R.string.pref_transactiontimeout),
                                    RoutingServiceBackend.DEFAULT_TRANSACTIONTIMEOUT),
                            TimeUnit.SECONDS
                    )
                    .subscribe(
                            {
                                val globaldisp = discoveryDispoable.getAndSet(null)
                                globaldisp.dispose()
                                d.dispose()
                            }
                    ) { err -> Log.e(TAG, "error while timing out scan: $err") }
            mGattDisposable.add(timeoutDisp)
        }
        d.add(newd)
        return d
    }

    /**
     * start device discovery forever, dispose to cancel.
     * @return observable of HandshakeResult containing transaction stats
     */
    override fun discoverForever(): Observable<HandshakeResult> {
        discoveryPersistent.set(true)
        discoverOnce(true)
        return observeTransactions()
                .doOnSubscribe { disp ->
                    mGattDisposable.add(disp)
                }
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
    override fun observeTransactions(): Observable<HandshakeResult> {
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

    private fun rewindSession(session: LeDeviceSession) {
        Log.v(TAG, "rewinding session")
        session.stage = TransactionResult.STAGE_UPGRADE
    }


    private fun handleConnection(connection: Observable<RxBleConnection>, device: RxBleDevice): Observable<HandshakeResult> {
        return connection
            .map { c -> CachedLEConnection(c, channels, operationsScheduler) }
            .flatMap { clientConnection ->
                serverSubject
                    .flatMapObservable{ connection ->
                        Log.v(TAG, "stating stages")
                        getLuidClient(clientConnection)
                            .toObservable()
                            .mergeWith(
                                getLuidServer(connection)
                                    .doOnError { err -> Log.e(TAG, "server error on retrieving luid $err") }
                            )
                            .subscribeOn(operationsScheduler)
                            .doOnError { err -> Log.e(TAG, "client error on retrieving luid $err") }
                            .firstOrError()
                            .onErrorResumeNext(Single.never())
                            .flatMapObservable { luid ->
                                Log.v(TAG, "successfully connected to ${luid.hashAsUUID}")
                                val s = sessionCache[luid.hashAsUUID!!]
                                    ?: LeDeviceSession(
                                        device.bluetoothDevice,
                                        myLuid.get(),
                                        clientConnection,
                                        connection,
                                        luid
                                    )

                                sessionCache.putIfAbsent(luid.hashAsUUID!!, s)
                                //TODO: handle this disposable
                                val disp = Single.just(luid)
                                    .delay(
                                        preferences.getLong(mContext.getString(R.string.pref_peercachedelay), (20 * 60).toLong()),
                                        TimeUnit.SECONDS,
                                        operationsScheduler
                                    )
                                    .subscribe(
                                        { l: LuidPacket ->
                                            Log.v(TAG, "peer $device timed out, removing from nearby peer cache")
                                            sessionCache.remove(l.luidVal)
                                        },
                                        { err -> Log.e(TAG, "error waiting to remove cached peer $device: $err") }
                                    )
                                Log.v(TAG, "initializing session")
                                initializeProtocol(s, TransactionResult.STAGE_LUID)
                                    .doOnError { e -> Log.e(TAG, "failed to initialize protocol $e") }
                                    .flatMapObservable { session ->
                                        Log.v(TAG, "session initialized")
                                        val d = connection.connection.getOnCharacteristicReadRequest(UUID_SEMAPHOR)
                                            .concatMapCompletable { trans ->
                                                val remoteluid = bytes2uuid(trans.value)
                                                val remotesession = sessionCache[remoteluid]

                                                when {
                                                    remotesession == null -> {
                                                        Log.e(TAG, "remote session for $remoteluid was null")
                                                        trans.sendReply(null, BluetoothGatt.GATT_FAILURE)
                                                    }
                                                    remotesession.locked -> {
                                                        Log.e(TAG, "attempted to rewind session $remoteluid but was locked")
                                                        trans.sendReply(null, BluetoothGatt.GATT_FAILURE)
                                                    }
                                                    else -> {
                                                        Log.v(TAG, "remote session $remoteluid valid, commencing rewind")
                                                        rewindSession(remotesession)
                                                        trans.sendReply(null, BluetoothGatt.GATT_SUCCESS )
                                                    }
                                                }
                                            }
                                            .subscribe()

                                        /*
                                         * this is kind of hacky. this observable chain is the driving force of our state machine
                                         * state transitions are handled when our LeDeviceSession is subscribed to.
                                         * each transition causes the session to emit the next state and start over
                                         * since states are only handled on subscribe or on a terminal error, there
                                         * shouldn't be any races or reentrancy issues
                                         */
                                        session.observeStage()
                                            .doOnNext { stage -> Log.v(TAG, "handling stage: $stage") }
                                            .concatMap {
                                                Single.zip(
                                                    session.singleClient(),
                                                    session.singleServer(),
                                                    { client, server ->
                                                        server(connection)
                                                            .doOnSuccess { Log.v(TAG, "server handshake completed") }
                                                            .zipWith(client(clientConnection), { first, second ->
                                                                android.util.Pair(first, second)
                                                            })
                                                            .toObservable()
                                                            .doOnSubscribe { Log.v("debug", "client handshake subscribed") }
                                                    }

                                                ).flatMapObservable { result -> result }
                                                    .subscribeOn(clientScheduler)
                                                    .doOnError { err ->
                                                        Log.e(TAG, "transaction error $err")
                                                        err.printStackTrace() //TODO: handle crashlytics
                                                        session.stage = TransactionResult.STAGE_TERMINATE
                                                    }
                                                    // retry on error
                                                    .onErrorReturnItem(android.util.Pair(OptionalBootstrap.empty(), TransactionResult(
                                                        TransactionResult.STAGE_ADVERTISE,
                                                        device.bluetoothDevice,
                                                        luid.hashAsUUID!!
                                                    )))
                                                    .doOnNext { transactionResult ->
                                                        if (session.stage == TransactionResult.STAGE_SUSPEND &&
                                                            !transactionResult.second.hasResult() &&
                                                            !transactionResult.first.isPresent) {
                                                            Log.v(TAG, "session $device suspending, no bootstrap")
                                                            session.unlock()
                                                        }
                                                        session.stage = transactionResult.second.nextStage
                                                    }
                                                    .filter { pair -> pair.second.hasResult() || pair.first.isPresent }

                                            }
                                            .takeUntil { result -> result.second.nextStage == TransactionResult.STAGE_TERMINATE }
                                            .map { pair ->
                                                when {
                                                    pair.second.hasResult() -> pair.second.result
                                                    else -> pair.first.item
                                                }
                                            }
                                            .flatMapSingle { bootstrapRequest -> bootstrapWifiP2p(bootstrapRequest) }
                                            .doOnNext {
                                                Log.v(TAG, "wifi direct bootstrap complete, unlocking session.")
                                                session.unlock()
                                                activeLuids.remove(session.remoteLuid.luidVal)
                                            }
                                            .doOnError { err ->
                                                Log.e(TAG, "session ${session.remoteLuid} ended with error $err")
                                                err.printStackTrace()
                                                when (err) {
                                                    is BleDisconnectedException -> session.stage = TransactionResult.STAGE_TERMINATE
                                                    else -> session.stage = TransactionResult.STAGE_SUSPEND
                                                }
                                                session.unlock()
                                            }
                                            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
                                            .doFinally {
                                                Log.e(TAG, "TERMINATION: session $device terminated")
                                                // if we encounter any errors or terminate, remove cached connections
                                                // as they may be tainted
                                                sessionCache.remove(session.remoteLuid.luidVal)
                                                activeLuids.remove(session.remoteLuid.luidVal)
                                                connectionCache.remove(device.macAddress)
                                                connection.dispose()
                                                session.unlock()
                                                removeConnection(device.macAddress)
                                                wifiDirectRadioModule.removeGroup().subscribe(
                                                    { Log.v(TAG, "successfually cleanued up wifi direct group after termination") },
                                                    { err ->
                                                        Log.e(TAG, "failed to cleanup wifi direct group after termination")
                                                        FirebaseCrashlytics.getInstance().recordException(err)
                                                    }
                                                )
                                                myLuid.set(UUID.randomUUID()) // randomize luid for privacy
                                            }

                                    }

                            }
                    }
            }
    }
    /**
     * starts the gatt server in the background.
     * NOTE: this function contains all the logic for running the state machine.
     * this function NEEDS to be called for the device to be connectable
     * @return false on failure
     */
    override fun startServer() {
        val started = serverStarted.getAndSet(true)
        if (started) {
            return
        }

        val config = ServerConfig.newInstance(Timeout(5, TimeUnit.SECONDS))
                .addService(mService)

        /*
         * NOTE: HIGHLY IMPORTANT: ACHTUNG!!
         * this may seem like black magic, but gatt server connections are registered for
         * BOTH incoming gatt connections AND outgoing connections. In fact, I cannot find a way to
         * distinguish incoming and outgoing connections. So every connection, even CLIENT connections
         * that we just initiated show up as emissions from this observable. Really wonky right?
         *
         * In a perfect world I would refactor my fork of RxAndroidBle to fix this, but the changes
         * required to do that are very invasive and probably not worth it in the long run.
         *
         * As a result, gatt client connections are seemingly thrown away and unhandled. THIS IS FAKE NEWS
         * they are handled here.
         */
        val d = mClient.openServer(config)
                .doOnSubscribe { Log.v(TAG, "gatt server subscribed") }
                .doOnError { Log.e(TAG, "failed to open server") }
                .flatMapObservable { connectionRaw ->
                    Log.v(TAG, "gatt server initialized")
                    serverSubject.onSuccess(CachedLEServerConnection(connectionRaw, channels, operationsScheduler))
                    //TODO:
                    connectionRaw.getOnCharacteristicWriteRequest(UUID_HELLO)
                        .subscribeOn(operationsScheduler)
                        .flatMap { trans ->
                            Log.e(TAG, "hello from ${trans.remoteDevice}")
                            //accquire wakelock
                            acquireWakelock()
                            val luid = bytes2uuid(trans.value)
                            if (activeLuids.putIfAbsent(luid, true) == null ) {
                                trans.sendReply(null, BluetoothGatt.GATT_SUCCESS)
                                    .andThen(handleConnection(trans.remoteDevice.establishConnection(false), trans.remoteDevice))
                            } else {
                                trans.sendReply(null, BluetoothGatt.GATT_FAILURE)
                                    .toSingleDefault(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
                                    .toObservable()
                            }
                        }
                        .doOnEach { e -> Log.e(TAG, "failed to read hello characteristic: $e") }

                }
                .doOnDispose {
                    Log.e(TAG, "gatt server disposed")
                    transactionErrorRelay.accept(IllegalStateException("gatt server disposed"))
                    stopServer()
                }
                .doOnError { err ->
                    Log.e(TAG, "gatt server shut down with error: $err")
                    err.printStackTrace()
                }
                .doOnComplete { Log.e(TAG, "gatt server completed. This shouldn't happen") }
                .retry()
                .repeat()
                .doOnNext { releaseWakeLock() }
                .subscribe(transactionCompleteRelay)
        mGattDisposable.add(d)
        startAdvertise()
    }

    /**
     * stop our server and stop advertising
     */
    override fun stopServer() {
        if (!serverStarted.get()) {
            return
        }
        stopAdvertise()
        serverStarted.set(false)
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
                    .zipWith(lockState.filter { p -> !p }.firstOrError(), { ch, _ -> ch })
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
        }

        @get:Synchronized
        @Suppress("Unused")
        val characteristic: BluetoothGattCharacteristic
            get() {
                if (released) {
                    throw ConcurrentModificationException()
                }
                lockedCharactersitic.release()
                return lockedCharactersitic.characteristic
            }

        val uuid: UUID
            get() = lockedCharactersitic.uuid

    }

    init {
        observeTransactionComplete()
    }
}