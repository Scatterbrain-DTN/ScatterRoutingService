package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.*
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.SingleSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.*
import net.ballmerlabs.uscatterbrain.BuildConfig
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
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
import kotlin.collections.ArrayList

data class OptionalBootstrap<T>(
        val item: T? = null,
        val err: Throwable? = null
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

        fun <T> err(throwable: Throwable): OptionalBootstrap<T> {
            return OptionalBootstrap(item = null, err = throwable)
        }
    }

    fun isError(): Boolean {
        return err != null
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

    private val isAdvertising = BehaviorSubject.create<Pair<OptionalBootstrap<AdvertisingSet>, Int>>()
    private val advertisingLock = AtomicReference(false)
    private val advertisingDataUpdated = PublishSubject.create<Int>()
    private val advertiseSetCallback = object :AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?,
            txPower: Int,
            status: Int
        ) {
            super.onAdvertisingSetStarted(advertisingSet, txPower, status)
            if (advertisingSet != null) {
                isAdvertising.onNext(Pair(OptionalBootstrap.of(advertisingSet), status))
            } else {
                isAdvertising.onNext(Pair(OptionalBootstrap.empty(), status))
            }
            Log.v(TAG, "successfully started advertise $status")
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            super.onAdvertisingSetStopped(advertisingSet)
            Log.e(TAG, "advertise stopped")
            isAdvertising.onNext(Pair(OptionalBootstrap.empty(), ADVERTISE_SUCCESS))
        }

        override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) {
            super.onScanResponseDataSet(advertisingSet, status)
            Log.v(TAG, "set advertising data")
            advertisingDataUpdated.onNext(status)
        }
    }


    // scatterbrain gatt service object
    private val mService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()

    private val discoveryPersistent = AtomicReference(false)

    //block all incoming connections during sensitive operations like updating connection cache
    private val incomingConnectionLock = BehaviorSubject.create<Boolean>()

    private val serverSubject = SingleSubject.create<CachedLEServerConnection>()


    // a "channel" is a characteristc that protobuf messages are written to.
    private val channels = ConcurrentHashMap<UUID, LockedCharactersitic>()
    companion object {
        const val TAG = "BluetoothLE"

        // scatterbrain service uuid. This is the same for every scatterbrain router.
        val SERVICE_UUID: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")

        val ADVERTISE_DATA_LUID_UUID = UUID.fromString("e1fe2989-e9b1-4d72-bd5d-35de26d82127")

        // GATT characteristic uuid for semaphor used for a device to  lock a channel.
        // This is to overcome race conditions caused by the statefulness of the GATT DB
        // we really shouldn't need this but android won't let us have two GATT DBs
        val UUID_SEMAPHOR: UUID = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")

        // characteristic to initiate a session
        val UUID_HELLO: UUID = UUID.fromString("5d1b424e-ff15-49b4-b557-48274634a01a")

        // number of channels. This can be increased or decreased for performance
        private const val NUM_CHANNELS = 8

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

        fun uuid2bytes(uuid: UUID?): ByteArray? {
            uuid?: return null
            val buffer = ByteBuffer.allocate(16)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            return buffer.array()
        }

        fun bytes2uuid(bytes: ByteArray?): UUID? {
            bytes?: return null
            val buffer = ByteBuffer.wrap(bytes)
            val high = buffer.long
            val low = buffer.long
            return UUID(high, low)
        }
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
        isAdvertising.onNext(Pair(OptionalBootstrap.empty(), AdvertisingSetCallback.ADVERTISE_SUCCESS))
        incomingConnectionLock.onNext(false)
        Log.e(TAG, "init")
    }

    private val mGattDisposable = CompositeDisposable()
    private val discoveryDispoable = AtomicReference<Disposable>()

    private val transactionCompleteRelay = PublishRelay.create<HandshakeResult>()

    // luid is a temporary unique identifier used for a single transaction.
    private val myLuid = AtomicReference(UUID.randomUUID())

    private val connectionCache = ConcurrentHashMap<UUID, CachedLEConnection>()
    private val transactionErrorRelay = PublishRelay.create<Throwable>()

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

    private fun getLuidClient(conn: CachedLEConnection): Single<LuidPacket> {
        return conn.readLuid()
                .doOnError { err ->
                    Log.e(TAG, "error while receiving luid packet: $err")
                    err.printStackTrace()
                }
                .flatMap { luidPacket ->
                    if (luidPacket.protoVersion != ScatterRoutingService.PROTO_VERSION) {
                        Log.e(TAG, "error, device connected with invalid protocol version: " + luidPacket.protoVersion)
                        return@flatMap Single.error(java.lang.IllegalStateException("invalid proto version"))
                    }

                    Log.e("debug", "version: ${luidPacket.protoVersion} hash ${luidPacket.hashAsUUID}")

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
        if (!wakeLock.isHeld)
            wakeLock.acquire((10 * 60 * 1000).toLong())
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld)
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

    private fun mapAdvertiseComplete(state: Boolean): Completable {
        return isAdvertising
                .takeUntil { v -> (v.first.isPresent == state) || (v.second != AdvertisingSetCallback.ADVERTISE_SUCCESS)}
                .flatMapCompletable { v ->
                    if (v.second != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        Completable.error(IllegalStateException("failed to complete advertise task: ${v.second}"))
                    } else {
                        Completable.complete()
                    }
                }

    }

    /**
     * start offloaded advertising. This should continue even after the phone is asleep
     */
    override fun startAdvertise(luid: UUID?): Completable {
        return isAdvertising
            .firstOrError()
            .flatMapCompletable { v ->
            if (v.first.isPresent && (v.second == AdvertisingSetCallback.ADVERTISE_SUCCESS))
                Completable.complete()
            else
                Completable.defer {
                    Log.v(TAG, "Starting LE advertise")
                    val settings = AdvertisingSetParameters.Builder()
                        .setConnectable(true)
                        .setScannable(true)
                        .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                        .setLegacyMode(true)
                        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                        .build()
                    val serviceData = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build()

                    val responsedata = if (luid != null) {
                        AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceUuid(ParcelUuid(luid))
                            .build()
                    } else {
                        AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .build()
                    }
                    if (!advertisingLock.getAndSet(true)) {
                      mAdvertiser.startAdvertisingSet(settings, serviceData, responsedata, null, null, advertiseSetCallback)
                    }
                    mapAdvertiseComplete(true)
                }
        }.doOnError { err ->
            Log.e(TAG, "error in startAdvertise $err")
            err.printStackTrace()
        }.doFinally {
                Log.v(TAG, "startAdvertise completed")
                advertisingLock.set(false)
        }
    }

    private fun setAdvertisingLuid(luid: UUID): Completable {
        return Completable.defer {
            isAdvertising
                .firstOrError()
                .flatMapCompletable { v ->
                if (v.first.isPresent) {
                    awaitAdvertiseDataUpdate()
                        .doOnSubscribe {
                            v.first.item!!.setScanResponseData(AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .setIncludeTxPowerLevel(false)
                            .addServiceUuid(ParcelUuid(luid))
                            .build())
                        }
                } else {
                    Completable.error(IllegalStateException("failed to set advertising data randomizeLuid"))
                }
            }
        }
            .doOnComplete { Log.v(TAG, "successfully set luid $luid") }
    }

    private fun awaitAdvertiseDataUpdate(): Completable {
        return advertisingDataUpdated
            .firstOrError()
            .flatMapCompletable { status ->
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS)
                    Completable.complete()
                else
                    Completable.error(IllegalStateException("failed to set advertising data"))
            }
    }

    private fun randomizeLuid(): Completable {
        return Single.just(UUID.randomUUID())
            .flatMapCompletable { luid ->
                myLuid.set(luid)
                val hashed = getHashUuid(luid)!!
                setAdvertisingLuid(hashed)
            }
    }


    private fun removeLuid(): Completable {
        return Completable.defer {
            isAdvertising
                .firstOrError()
                .flatMapCompletable { v ->
                    if (v.first.isPresent) {
                        awaitAdvertiseDataUpdate()
                            .doOnSubscribe {
                                v.first.item!!.setScanResponseData(AdvertiseData.Builder()
                                    .setIncludeDeviceName(false)
                                    .setIncludeTxPowerLevel(false)
                                    .build())
                            }
                    } else {
                        Completable.error(IllegalStateException("failed to set advertising data removeLuid"))
                    }
                }
        }
            .doOnComplete { Log.v(TAG, "successfully removed luid") }
    }

    /**
     * stop offloaded advertising
     */
    override fun stopAdvertise(): Completable {
        Log.v(TAG, "stopping LE advertise")
        return Completable.fromAction {
            mAdvertiser.stopAdvertisingSet(advertiseSetCallback)
            mapAdvertiseComplete(false)
        }
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
                                        }
                                    .doOnError { err -> Log.e(TAG, "election server error $err") }
                                    .toSingleDefault(OptionalBootstrap.empty())
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

    override fun refreshPeers(): Observable<HandshakeResult> {
        return refreshInProgresss
                .firstOrError()
                .flatMapObservable { b ->
                    if (b) refreshInProgresss.takeUntil { v -> !v }
                        .flatMap {
                            Observable.fromIterable(connectionCache.entries)
                                .flatMapSingle { v ->
                                    initiateOutgoingConnection(v.value, v.key)
                                }
                        }
                    else {
                        Observable.empty()
                    }
                }
    }

    private fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return if (scanResult.scanRecord.serviceUuids.size == 2) {
            val serviceIndex = scanResult.scanRecord.serviceUuids.indexOf(ParcelUuid(SERVICE_UUID))
            val luidIndex = if (serviceIndex == 0) 1 else 0
            scanResult.scanRecord.serviceUuids[luidIndex]?.uuid
        } else {
            null
        }
    }

    private fun processScanResult(scanResult: ScanResult): Single<HandshakeResult> {
        Log.d(TAG, "scan result: " + scanResult.bleDevice.macAddress)
        return Single.defer {
            val remoteUuid = getAdvertisedLuid(scanResult)
            Log.v(TAG, "scan result has luid ${remoteUuid?:"null"}")
            val initialCachedConnection = if (remoteUuid != null) connectionCache[remoteUuid] else null
            when {
                remoteUuid != null && initialCachedConnection != null -> {
                    initiateOutgoingConnection(initialCachedConnection, remoteUuid)
                }
                else -> {
                    establishConnectionCached(scanResult.bleDevice, remoteUuid)
                        .flatMap { cached ->
                            cached.connection
                                .concatMapSingle { raw ->
                                    Log.v(TAG, "attempting to read hello characteristic")
                                    raw.readCharacteristic(UUID_HELLO)
                                        .flatMap { luid ->
                                            val luidUuid = bytes2uuid(luid)!!
                                            Log.v(TAG, "read remote luid from GATT $luidUuid")
                                            initiateOutgoingConnection(
                                                cached,
                                                luidUuid
                                            )
                                        }
                                }
                                .firstOrError()
                        }

                }
            }
        }
    }


    private fun initiateOutgoingConnection(cachedConnection: CachedLEConnection, luid: UUID): Single<HandshakeResult> {
        return Single.defer {
            Log.e(TAG, "client handling luid $luid")
                cachedConnection.connection
                    .firstOrError()
                    .flatMap { connection ->
                        val hash = getHashUuid(myLuid.get())!!
                        Log.v(TAG, "writing hashed luid $hash")
                        connection.writeCharacteristic(UUID_HELLO, uuid2bytes(hash)!!)
                            .doOnSuccess { res ->
                                Log.v(
                                    TAG,
                                    "successfully wrote uuid len ${res.size}"
                                )
                            }
                            .doOnError { e -> Log.e(TAG, "failed to write characteristic: $e") }
                            .ignoreElement()
                            .andThen (handleConnection(cachedConnection, cachedConnection.device, luid))
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


    /*
     * discover LE devices. Currently this runs forever and sets a global disposable
     * so we can kill it if we want.
     * This only scans for scatterbrain UUIDs
     * TODO: make sure this runs on correct scheduler
     */
    private fun discoverOnce(duration: Long, timeUnit: TimeUnit): Single<List<ScanResult>> {
        Log.d(TAG, "discover once called")
        return mClient.scanBleDevices(
                ScanSettings.Builder()
                        .setScanMode(parseScanMode())
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setShouldCheckLocationServicesState(true)
                        .build(),
                ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build())
            .take(duration, timeUnit, operationsScheduler)
            .reduce(ArrayList(), { list: ArrayList<ScanResult>, result ->
                list.add(result)
                list
            })
            .map { l -> l.toList() }
    }

    /**
     * start device discovery forever, dispose to cancel.
     * @return observable of HandshakeResult containing transaction stats
     */
    override fun discoverForever(): Observable<HandshakeResult> {
        discoveryPersistent.set(true)
        return Observable.defer {
            discoverOnce(30, TimeUnit.SECONDS)
                .flatMapObservable { results ->
                    Log.e(TAG, "received scan results ${results.size}")
                    Observable.fromIterable(results)
                        .distinct { res -> res.bleDevice.macAddress }
                        .concatMapSingle { scanResult ->
                            processScanResult(scanResult)
                                .doOnSuccess {
                                    Log.e(
                                        TAG,
                                        "I DID A DONE! transaction for ${scanResult.bleDevice.macAddress} complete"
                                    )
                                }
                                .doOnError { err ->
                                    Log.e(
                                        TAG,
                                        "transaction for ${scanResult.bleDevice.macAddress} failed: $err"
                                    )
                                    err.printStackTrace()
                                }
                        }
                }
        }
        .repeat()
        .retry()
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

    private fun removeWifiDirectGroup(): Completable {
        return wifiDirectRadioModule.removeGroup()
                    .doOnError { err ->
                        Log.e(TAG, "failed to cleanup wifi direct group after termination")
                        FirebaseCrashlytics.getInstance().recordException(err)
                    }
    }

    private fun establishConnectionCached(device: RxBleDevice, luid: UUID? = null): Single<CachedLEConnection> {
        val connectSingle =
            incomingConnectionLock.takeUntil { v -> !v }
                .ignoreElements()
                .andThen(
            Single.fromCallable {
                incomingConnectionLock.onNext(true)
                Log.e(
                    TAG,
                    "establishing cached connection to ${device.macAddress}, ${luid ?: "null"}, ${connectionCache.size} devices connected"
                )
                val connection = if (luid == null) null else connectionCache[luid]
                if (connection != null) {
                    Log.e(TAG, "cache HIT")
                    connection
                } else {
                    val rawConnection = device.establishConnection(false)
                        .doOnNext {
                            Log.e(
                                TAG,
                                "established cached connection to ${device.macAddress}"
                            )
                        }
                    val newconnection = CachedLEConnection(channels, clientScheduler, device)
                    val c = retrieveLuidIfNull(luid, rawConnection)
                        .map { connectionPair ->
                            Log.v(TAG, "adding connection to cache under ${connectionPair.first}")
                            Log.e(TAG, "cache MISS, ${connectionCache.size} devices connected")
                            val collision =
                                connectionCache.putIfAbsent(connectionPair.first, newconnection)
                            if (collision == null) {
                                newconnection.setOnDisconnect {
                                    val conn = connectionCache.remove(connectionPair.first)
                                    conn?.dispose()
                                    if (connectionCache.isEmpty()) {
                                        myLuid.set(UUID.randomUUID())
                                        removeLuid()
                                    } else {
                                        Completable.complete()
                                    }
                                }
                                connectionPair.second
                            } else {
                                throw IllegalStateException("what the absolute hecking heck")
                            }
                        }

                    newconnection.subscribeConnection(c)
                    newconnection

                }
            }.doFinally { incomingConnectionLock.onNext(false) })

        return Single.defer {
            /*
            if (connectionCache.isEmpty())
                randomizeLuid()
                    .andThen(connectSingle)
            else
             */
                setAdvertisingLuid(getHashUuid(myLuid.get())!!)
                    .andThen(connectSingle)
        }
    }

    private fun retrieveLuidIfNull(luid: UUID?, conn: Observable<RxBleConnection>): Observable<Pair<UUID, RxBleConnection>> {
        return if (luid != null)
            conn.map { c -> Pair(luid, c) }
        else {
            conn.flatMapSingle { c ->
                c.readCharacteristic(UUID_HELLO)
                    .map { bytes ->
                        Log.v(TAG, "retrieveLuidIfNull hello characteristic read")
                        Pair(bytes2uuid(bytes)!!, c)
                    }
            }

        }
    }

    private fun handleConnection(connection: CachedLEConnection, device: RxBleDevice, luid: UUID): Single<HandshakeResult> {
        return Single.just(connection)
            .flatMap { clientConnection ->
                serverSubject
                    .flatMap { connection ->
                                Log.v(TAG, "successfully connected to ${luid}")
                                val s = LeDeviceSession(
                                        device.bluetoothDevice,
                                        myLuid.get(),
                                        clientConnection,
                                        connection,
                                        luid
                                    )
                                Log.v(TAG, "initializing session")
                                initializeProtocol(s, TransactionResult.STAGE_LUID)
                                    .doOnError { e -> Log.e(TAG, "failed to initialize protocol $e") }
                                    .flatMap { session ->
                                        Log.v(TAG, "session initialized")

                                        /*
                                         * this is kind of hacky. this observable chain is the driving force of our state machine
                                         * state transitions are handled when our LeDeviceSession is subscribed to.
                                         * each transition causes the session to emit the next state and start over
                                         * since states are only handled on subscribe or on a terminal error, there
                                         * shouldn't be any races or reentrancy issues
                                         */
                                        session.observeStage()
                                            .doOnNext { stage -> Log.v(TAG, "handling stage: $stage") }
                                            .concatMap { stage ->
                                                Single.zip(
                                                    session.singleClient(),
                                                    session.singleServer(),
                                                    { client, server ->
                                                        server(connection)
                                                            .doOnError { err ->
                                                                Log.e(TAG, "error in gatt server transaction for ${device.macAddress}, stage: $stage, $err")
                                                                err.printStackTrace()
                                                            }
                                                            .doOnSuccess { Log.v(TAG, "server handshake completed") }
                                                            .onErrorReturn { err -> OptionalBootstrap.err(err) }
                                                            .zipWith(
                                                                client(clientConnection)
                                                                    .doOnSuccess { Log.v(TAG, "client handshake completed") }
                                                                    .doOnError { err ->
                                                                        Log.e(
                                                                            TAG,
                                                                            "error in gatt client transaction for ${device.macAddress}, stage: $stage, $err"
                                                                        )
                                                                        err.printStackTrace()
                                                                    }
                                                                    .onErrorReturn { TransactionResult(
                                                                        TransactionResult.STAGE_TERMINATE,
                                                                        device.bluetoothDevice,
                                                                        luid,
                                                                        err = true
                                                                    ) },
                                                                { first, second ->
                                                                Pair(first, second)
                                                            })
                                                    }

                                                ).flatMap { result -> result }
                                                    .flatMap { v ->
                                                        when {
                                                            v.first.isError() -> Single.error(v.first.err)
                                                            v.second.err -> Single.error(IllegalStateException("gatt client error"))
                                                            else -> Single.just(v)
                                                        }
                                                    }
                                                    .doOnError { err ->
                                                        Log.e(TAG, "transaction error $err")
                                                        err.printStackTrace() //TODO: handle crashlytics
                                                        session.stage = TransactionResult.STAGE_TERMINATE
                                                    }
                                                    .doOnSuccess { transactionResult ->
                                                        if (session.stage == TransactionResult.STAGE_SUSPEND &&
                                                            !transactionResult.second.hasResult() &&
                                                            !transactionResult.first.isPresent) {
                                                            Log.v(TAG, "session $device suspending, no bootstrap")
                                                            session.unlock()
                                                        }
                                                        session.stage = transactionResult.second.nextStage
                                                    }
                                                    .filter { pair -> pair.second.hasResult() || pair.first.isPresent }
                                                    .toObservable()


                                            }
                                            .takeUntil { result -> result.second.nextStage == TransactionResult.STAGE_TERMINATE }
                                            .map { pair ->
                                                when {
                                                    pair.second.hasResult() -> pair.second.result
                                                    else -> pair.first.item
                                                }
                                            }
                                            .flatMapSingle { bootstrapRequest ->
                                                bootstrapWifiP2p(bootstrapRequest)
                                                    .flatMap { r ->
                                                        removeWifiDirectGroup()
                                                            .onErrorComplete()
                                                            .toSingleDefault(r)
                                                    }
                                            }
                                            .firstOrError()
                                            .doOnSuccess {
                                                Log.v(TAG, "wifi direct bootstrap complete, unlocking session.")
                                                session.stage = TransactionResult.STAGE_TERMINATE
                                            }
                                            .doOnError { err ->
                                                Log.e(TAG, "session ${session.remoteLuid} ended with error $err")
                                                err.printStackTrace()
                                                session.stage = TransactionResult.STAGE_TERMINATE
                                            }
                                            .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL))
                                            .doFinally {
                                                Log.e(TAG, "TERMINATION: session $device terminated")
                                                // if we encounter any errors or terminate, remove cached connections
                                                // as they may be tainted
                                                //clientConnection.dispose()
                                                session.unlock()
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
        val d = startAdvertise()
            .andThen(
            mClient.openServer(config)
                .doOnSubscribe { Log.v(TAG, "gatt server subscribed") }
                .doOnError { Log.e(TAG, "failed to open server") }
                .flatMapObservable { connectionRaw ->
                    Log.v(TAG, "gatt server initialized")
                    serverSubject.onSuccess(CachedLEServerConnection(connectionRaw, channels, serverScheduler))
                    //TODO:
                    val write = connectionRaw.getOnCharacteristicWriteRequest(UUID_HELLO)
                        .subscribeOn(operationsScheduler)
                        .flatMapSingle { trans ->
                            Log.e(TAG, "hello from ${trans.remoteDevice.macAddress}")
                            //accquire wakelock
                            acquireWakelock()
                            val luid = bytes2uuid(trans.value)!!
                            Log.e(TAG, "server handling luid $luid")
                            incomingConnectionLock.takeUntil { v -> !v }
                                .ignoreElements()
                                .andThen(
                                trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                                    .andThen(establishConnectionCached(trans.remoteDevice, luid))
                                    .flatMap { connection ->
                                        handleConnection(
                                            connection,
                                            trans.remoteDevice,
                                            luid
                                        )
                                    }
                                    .doOnError { err -> Log.e(TAG, "error in handleConnection $err") }
                                    .onErrorReturnItem(HandshakeResult(0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL)))
                        }
                        .doOnError { e -> Log.e(TAG, "failed to read hello characteristic: $e") }
                        .doOnNext { t -> Log.v(TAG, "transactionResult ${t.success}") }
                        .retry()
                        .repeat()


                    val read = connectionRaw.getOnCharacteristicReadRequest(UUID_HELLO)
                        .subscribeOn(operationsScheduler)
                        .doOnSubscribe { Log.v(TAG, "hello characteristic read subscribed") }
                        .flatMapCompletable { trans ->
                            val luid = getHashUuid(myLuid.get())
                            Log.v(TAG, "hello characteristic read, replying with luid $luid}")
                            trans.sendReply(uuid2bytes(luid), BluetoothGatt.GATT_SUCCESS)
                        }
                        .doOnError { err -> Log.e(TAG, "error in hello characteristic read: $err") }
                        .retry()
                        .repeat()

                    write.mergeWith(read)
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
            ).subscribe(transactionCompleteRelay)
        mGattDisposable.add(d)
    }

    /**
     * stop our server and stop advertising
     */
    override fun stopServer() {
        if (!serverStarted.get()) {
            return
        }
        stopAdvertise()
            .subscribe()
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