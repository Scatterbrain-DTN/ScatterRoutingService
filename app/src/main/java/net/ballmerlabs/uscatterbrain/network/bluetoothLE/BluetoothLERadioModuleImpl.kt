package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
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
 * trival and correct way of discarding duplicate connections, we cannot do that here because
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
        @param:Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) private val clientScheduler: Scheduler,
        private val mServer: RxBleServer,
        private val mClient: RxBleClient,
        private val wifiDirectRadioModule: WifiDirectRadioModule,
        private val datastore: ScatterbrainDatastore,
        private val powerManager: PowerManager,
        private val preferences: RouterPreferences
) : BluetoothLEModule {
    private val wakeLock = AtomicReference<WakeLock?>()

    companion object {
        const val TAG = "BluetoothLE"
        const val CLIENT_CONNECT_TIMEOUT = 10
        // scatterbrain service uuid. This is the same for every scatterbrain router.
        val SERVICE_UUID: UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")

        // GATT characteristic uuid for semaphor used for a device to  lock a channel.
        // This is to overcome race conditions caused by the statefulness of the GATT DB
        // we really shouldn't need this but android won't let us have two GATT DBs
        val UUID_SEMAPHOR: UUID = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")

        // a "channel" is a characteristc that protobuf messages are written to.
        val channels = ConcurrentHashMap<UUID, LockedCharactersitic>()

        // number of channels. This can be increased or decreased for performance
        private const val NUM_CHANNELS = 8

        // scatterbrain gatt service object
        val mService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        //avoid triggering concurrent peer refreshes
        val refreshInProgresss = BehaviorRelay.create<Boolean>()
        
        val discoveryPersistent = AtomicReference(false)

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

    private val connectedLuids = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
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

    /*
     * due to quirks in the way android handles bluetooth, it may be safest in some cases
     * to restart the discovery proces entirely. This does not terminate any observables
     * listening for transactions or scan results, but it will forcibly disconnect all currently
     * connected devices.
     *
     * Note: if we aren't currently discovering forever this just terminates the current discovery
     */
    private fun restartScan() {
        connectionCache.forEach {
            it.value.dispose()
        }
        connectionCache.clear()
        discoveryDispoable.getAndUpdate { d ->
            d?.dispose()
            null
        }
        if (discoveryPersistent.get()) {
            discoverOnce(true)
        }
    }

    private fun observeTransactionComplete() {
        val d = transactionCompleteRelay.subscribe(
                {
                    Log.v(TAG, "transaction complete, randomizing luid")
                    releaseWakeLock()
                    //for safety, disconnect all devies and restart scanning after a successful transaction
                    restartScan()
                    // we need to randomize the luid every transaction or devices can be tracked
                    myLuid.set(UUID.randomUUID())
                }
        ) { err: Throwable -> Log.e(TAG, "error in transactionCompleteRelay $err") }
        mGattDisposable.add(d)
    }

    /*
     * we should always hold a wakelock directly after the adapter wakes up the device
     * when a scatterbrain uuid is detected. The adapter is responsible for waking up the device
     * via offloaded scanning, but NOT for keeping it awake.
     */
    private fun acquireWakelock() {
        wakeLock.updateAndGet {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mContext.getString(R.string.wakelock_tag))
                    .apply { acquire(10*60*1000) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock.getAndUpdate { null }?.release()
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
    private fun initializeProtocol(device: BluetoothDevice): Single<LeDeviceSession> {
        Log.v(TAG, "initialize protocol")
        val session = LeDeviceSession(device, myLuid)
        
        /*
         * luid hashed stage exchanges hashed luid packets (for election purposes)
         * hashed packets are stored for later verification. 
         * hashed packets are also used to weed out and terminate duplicate connections
         */
        session.addStage(
                TransactionResult.STAGE_LUID_HASHED,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server luid hashed stage")
                    session.luidStage.selfHashed
                            .flatMapCompletable { luidpacket: LuidPacket ->
                                serverConn.serverNotify(luidpacket)
                            }.toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client luid hashed stage")
            conn.readLuid()
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving luid packet: $err") }
                    .observeOn(clientScheduler)
                    .map { luidPacket: LuidPacket ->
                        if (luidPacket.protoVersion != ScatterRoutingService.PROTO_VERSION) {
                            Log.e(TAG, "error, device connected with invalid protocol version: " + luidPacket.protoVersion)
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                        }

                        val hashUUID = luidPacket.hashAsUUID
                        Log.e("debug", "version: ${luidPacket.protoVersion} hash ${luidPacket.hashAsUUID}")
                        if (connectedLuids.contains(hashUUID)) {
                            Log.e(TAG, "device: $device already connected")

                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device, err = true)
                        } else {
                            connectedLuids.add(hashUUID)
                        }

                        Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.valCase)
                        session.luidStage.addPacket(luidPacket)
                        TransactionResult(TransactionResult.STAGE_LUID, device)
                    }
        }
        
        /*
         * luid stage reveals unhashed packets after all hashed packets are collected
         * hashes are compared to unhashed packets
         */
        session.addStage(
                TransactionResult.STAGE_LUID,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server luid stage")
                    session.luidStage.getSelf()
                            .flatMapCompletable { luidpacket: LuidPacket ->
                                session.luidStage.addPacket(luidpacket)
                                serverConn.serverNotify(luidpacket)
                            }.toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client luid stage")
            session.luidStage.selfHashed
                    .flatMap { selfHashed ->
                        session.luidStage.addPacket(selfHashed)
                        conn.readLuid()
                                .doOnSuccess { luidPacket: LuidPacket ->
                                    Log.v(TAG, "client handshake received unhashed luid packet: " + luidPacket.luid)
                                    session.luidStage.addPacket(luidPacket)
                                }
                                .doOnError { err: Throwable -> Log.e(TAG, "error while receiving luid packet: $err") }
                                .flatMapCompletable { luidPacket: LuidPacket ->
                                    session.luidStage.verifyPackets()
                                            .doOnComplete { session.luidMap[device.address] = luidPacket.luid }
                                }
                                .toSingleDefault(TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ADVERTISE, device))
                                .doOnError { err: Throwable -> Log.e(TAG, "luid hash verify failed: $err") }
                                .onErrorReturnItem(TransactionResult(TransactionResult.STAGE_EXIT, device))
                    }
        }
        
        /*
         * if no one cheats and sending luids, we exchange advertise packets. 
         * This is really boring currently because all scatterbrain routers offer the same capabilities
         */
        session.addStage(
                TransactionResult.STAGE_ADVERTISE,
                l@{ serverConn ->
                    Log.v(TAG, "gatt server advertise stage")
                    return@l serverConn.serverNotify(AdvertiseStage.self)
                            .toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client advertise stage")
            conn.readAdvertise()
                    .doOnSuccess { Log.v(TAG, "client handshake received advertise packet") }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving advertise packet: $err") }
                    .map { advertisePacket: AdvertisePacket ->
                        session.advertiseStage.addPacket(advertisePacket)
                        TransactionResult(TransactionResult.STAGE_ELECTION_HASHED, device)
                    }
        }
        
        /*
         * the leader election state is really complex, it is a good idea to check the documentation in
         * VotingStage.kt to figure out how this works.
         */
        session.addStage(
                TransactionResult.STAGE_ELECTION_HASHED,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server election hashed stage")
                    val packet = session.votingStage.getSelf(true, selectProvides())
                    if (BuildConfig.DEBUG && !packet.isHashed) {
                        error("Assertion failed")
                    }
                    session.votingStage.addPacket(packet)
                    serverConn.serverNotify(packet)
                            .toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client election hashed stage")
            conn.readElectLeader()
                    .doOnSuccess { Log.v(TAG, "client handshake received hashed election packet") }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving election packet: $err") }
                    .map { electLeaderPacket: ElectLeaderPacket ->
                        session.votingStage.addPacket(electLeaderPacket)
                        TransactionResult(TransactionResult.STAGE_ELECTION, device)
                    }
        }

        /*
         * see above
         * Once the election stage is finished we move on to the actions decided by the result of the election
         */
        session.addStage(
                TransactionResult.STAGE_ELECTION,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server election stage")
                    session.luidStage.getSelf()
                            .flatMapCompletable { luidPacket: LuidPacket ->
                                if (BuildConfig.DEBUG && luidPacket.isHashed) {
                                    error("Assertion failed")
                                }
                                val packet = session.votingStage.getSelf(false, selectProvides())
                                packet.tagLuid(luidPacket.luid)
                                session.votingStage.addPacket(packet)
                                serverConn.serverNotify(packet)
                            }.toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client election stage")
            conn.readElectLeader()
                    .flatMapCompletable { electLeaderPacket: ElectLeaderPacket? ->
                        electLeaderPacket!!.tagLuid(session.luidMap[device.address])
                        session.votingStage.addPacket(electLeaderPacket)
                        session.votingStage.verifyPackets()
                    }
                    .andThen(session.votingStage.determineUpgrade())
                    .map { provides: AdvertisePacket.Provides ->
                        Log.v(TAG, "election received provides: $provides")
                        val role: ConnectionRole = if (session.votingStage.selectSeme() == session.luidStage.luid) {
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
                                return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                            }
                            AdvertisePacket.Provides.BLE -> {
                                //we should do everything in BLE. slowwwww ;(
                                return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_IDENTITY, device)
                            }
                            AdvertisePacket.Provides.WIFIP2P ->
                                return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_UPGRADE, device)
                            else ->
                                return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                        }
                    }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving packet: $err") }
                    .doOnSuccess { Log.v(TAG, "client handshake received election result") }
                    .onErrorReturn { TransactionResult(TransactionResult.STAGE_EXIT, device) }
        }

        /*
         * if the election state decided we should upgrade, move to a new transport using an upgrade
         * packet exchange with our newly assigned role.
         * Currently this just means switch to wifi direct
         */
        session.addStage(
                TransactionResult.STAGE_UPGRADE,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server upgrade stage")
                    serverBootstrapWifiDirect(session, serverConn)
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client upgrade stage")
            if (session.role == ConnectionRole.ROLE_UKE) {
                return@addStage conn.readUpgrade()
                        .doOnSuccess { Log.v(TAG, "client handshake received upgrade packet") }
                        .doOnError { err: Throwable -> Log.e(TAG, "error while receiving upgrade packet: $err") }
                        .map { upgradePacket: UpgradePacket ->
                            val request: BootstrapRequest = WifiDirectBootstrapRequest.create(
                                    upgradePacket,
                                    ConnectionRole.ROLE_UKE
                            )
                            TransactionResult(TransactionResult.STAGE_EXIT, device, request)
                        }
            } else {
                return@addStage Single.just(TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device))
            }
        }

        /*
         * if we chose not to upgrade, proceed with exchanging identity packets
         */
        session.addStage(
                TransactionResult.STAGE_IDENTITY,
                { serverConn: CachedLEServerConnection ->
                    datastore.getTopRandomIdentities(
                            preferences.getInt(mContext.getString(R.string.pref_identitycap), 32)
                    )
                            .concatMapCompletable { packet: IdentityPacket -> serverConn.serverNotify(packet) }
                            .toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            conn.readIdentityPacket(mContext)
                    .repeat()
                    .takeWhile { identityPacket: IdentityPacket? ->
                        val end = !identityPacket!!.isEnd
                        if (!end) {
                            Log.v(TAG, "identitypacket end of stream")
                        }
                        end
                    }
                    .ignoreElements()
                    .toSingleDefault(TransactionResult(TransactionResult.STAGE_DECLARE_HASHES, device))
        }

        /*
         * if we chose not to upgrade, exchange delcare hashes packets
         */
        session.addStage(
                TransactionResult.STAGE_DECLARE_HASHES,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server declareHashes stage")
                    datastore.declareHashesPacket
                            .flatMapCompletable { packet: DeclareHashesPacket -> serverConn.serverNotify(packet) }
                            .toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            conn.readDeclareHashes()
                    .doOnSuccess { declareHashesPacket: DeclareHashesPacket ->
                        Log.v(TAG, "client handshake received declareHashes: " +
                                declareHashesPacket.hashes.size)
                    }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving declareHashes packet: $err") }
                    .map { declareHashesPacket: DeclareHashesPacket ->
                        session.setDeclareHashesPacket(declareHashesPacket)
                        TransactionResult(TransactionResult.STAGE_BLOCKDATA, device)
                    }
        }

        /*
         * if we chose not to upgrade, exchange messages V E R Y S L O W LY
         * TODO: put some sort of cap on file size to avoid hanging here for hours
         */
        session.addStage(
                TransactionResult.STAGE_BLOCKDATA,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server blockdata stage")
                    session.declareHashes
                            .flatMapCompletable { declareHashesPacket: DeclareHashesPacket ->
                                datastore.getTopRandomMessages(
                                        preferences.getInt(mContext.getString(R.string.pref_blockdatacap), 30),
                                        declareHashesPacket
                                )
                                        .concatMapCompletable { message: BlockDataStream ->
                                            serverConn.serverNotify(message.headerPacket)
                                                    .andThen(message.sequencePackets.concatMapCompletable { packet: BlockSequencePacket ->
                                                        serverConn.serverNotify(packet) })
                                        }
                            }.toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            conn.readBlockHeader()
                    .toFlowable()
                    .flatMap { blockHeaderPacket: BlockHeaderPacket ->
                        Flowable.range(0, blockHeaderPacket.hashList!!.size)
                                .map {
                                    BlockDataStream(
                                            blockHeaderPacket,
                                            conn.readBlockSequence()
                                                    .repeat(blockHeaderPacket.hashList.size.toLong())
                                    )
                                }
                    }
                    .takeUntil { stream: BlockDataStream ->
                        val end = stream.headerPacket.isEndOfStream
                        if (end) {
                            Log.v(TAG, "uke end of stream")
                        }
                        end
                    }
                    .concatMapSingle { m: BlockDataStream -> datastore.insertMessage(m).andThen(m.await()).toSingleDefault(0) }
                    .reduce { a: Int?, b: Int? -> Integer.sum(a!!, b!!) }
                    .toSingle()
                    .map { i: Int? -> HandshakeResult(0, i!!, HandshakeResult.TransactionStatus.STATUS_SUCCESS) }
                    .map { res: HandshakeResult ->
                        transactionCompleteRelay.accept(res)
                        TransactionResult(TransactionResult.STAGE_EXIT, device)
                    }
        }

        // set our starting stage
        session.stage = TransactionResult.STAGE_LUID_HASHED
        return Single.just(session) //TODO: this is ugly. Do this async
    }

    private fun removeConnection(device: String) {
        connectionCache.computeIfPresent(device) { _, value ->
            value.dispose()
            null
        }
    }
    
    /* attempt to bootstrap to wifi direct using upgrade packet (gatt server version) */
    private fun serverBootstrapWifiDirect(session: LeDeviceSession, serverConn: CachedLEServerConnection): Single<Optional<BootstrapRequest>> {
        return if (session.role == ConnectionRole.ROLE_SEME) {
            session.upgradeStage!!.upgrade
                    .flatMap { upgradePacket: UpgradePacket ->
                        val request: BootstrapRequest = WifiDirectBootstrapRequest.create(
                                upgradePacket,
                                ConnectionRole.ROLE_SEME
                        )
                        serverConn.serverNotify(upgradePacket)
                                .toSingleDefault(Optional.of(request))
                    }
        } else {
            Single.just(Optional.empty())
        }
    }

    /* attempt to bootstrap to wifi direct using upgrade packet (gatt client version) */
    private fun bootstrapWifiP2p(bootstrapRequest: BootstrapRequest): Single<HandshakeResult> {
        return wifiDirectRadioModule.bootstrapFromUpgrade(bootstrapRequest)
                .doOnError { err: Throwable ->
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

    /**
     * attempt to reinitiate a connection with all nearby peers and
     * run another transaction. This should be called sparingly if new data is available
     * If a refresh is already in progress this function calls oncomplete when the current
     * refresh is complete
     * @return completable
     */
    override fun refreshPeers(): Completable {
        return refreshInProgresss
                .firstOrError()
                .flatMapCompletable { b ->
                   if (b) refreshInProgresss.takeUntil { v -> !v }
                                .ignoreElements()
                   else {
                       restartScan()
                       awaitTransaction()
                   }
                }
                .doOnError { refreshInProgresss.accept(false) }
                .doOnComplete { refreshInProgresss.accept(false) }

    }

    /*
     * establish a GATT connection using caching to work around RxAndroidBle quirk
     */
    private fun establishConnection(device: RxBleDevice, timeout: Timeout): CachedLEConnection {
        val conn = connectionCache[device.macAddress]
        if (conn != null) {
            return conn
        }
        val connectionObs = device.establishConnection(false, timeout)
                .doOnDispose { removeConnection(device.macAddress) }
                .doOnError { removeConnection(device.macAddress) }
                .doOnNext {
                    Log.v(TAG, "successfully established connection")
                    Single.just(device)
                            .delay(
                                    preferences.getLong(mContext.getString(R.string.pref_peercachedelay), 10*60),
                                    TimeUnit.SECONDS,
                                    clientScheduler
                            )
                            .subscribe(
                                    { Log.v(TAG, "peer $device timed out, removing from nearby peer cache") },
                                    { err -> Log.e(TAG, "error waiting to remove cached peer $device: $err") }
                            )

                }
        val cached = CachedLEConnection(connectionObs, channels)
        connectionCache[device.macAddress] = cached

        return cached
    }

    /*
     * discover LE devices. Currently this runs forever and sets a global disposable
     * so we can kill it if we want.
     * This only scans for scatterbrain UUIDs
     * TODO: make sure this runs on correct scheduler
     */
    private fun discoverOnce(forever: Boolean): Disposable {
        Log.d(TAG, "discover once called")
        return discoveryDispoable.updateAndGet{
            d ->
            if (d != null) d
            else {
                val newd = mClient.scanBleDevices(
                        ScanSettings.Builder()
                                .setScanMode(parseScanMode())
                                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                                .setShouldCheckLocationServicesState(true)
                                .build(),
                        ScanFilter.Builder()
                                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                                .build())
                        .map { scanResult: ScanResult ->
                            Log.d(TAG, "scan result: " + scanResult.bleDevice.macAddress)
                            establishConnection(
                                    scanResult.bleDevice,
                                    Timeout(CLIENT_CONNECT_TIMEOUT.toLong(), TimeUnit.SECONDS)
                            )
                            /*
                             * WAIT? why are we not doing anything with our gatt connections??
                             * see the comment in startServer() for why
                             */

                        }
                        .ignoreElements()
                        .repeat()
                        .retry()
                        .doOnError { err: Throwable -> Log.e(TAG, "error with initial handshake: $err") }
                        .subscribe(
                                { Log.v(TAG, "handshake completed") },
                                { err: Throwable ->
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
                                        Log.v(TAG, "scan timed out")
                                        discoveryDispoable.getAndUpdate { compositeDisposable: Disposable? ->
                                            compositeDisposable?.dispose()
                                            null
                                        }
                                    }
                            ) { err: Throwable -> Log.e(TAG, "error while timing out scan: $err") }
                    mGattDisposable.add(timeoutDisp)
                }
                newd
            }

        }
    }

    /**
     * start device discovery forever, dispose to cancel.
     * @return observable of HandshakeResult containing transaction stats
     */
    override fun discoverForever(): Observable<HandshakeResult> {
        discoveryPersistent.set(true)
        discoverOnce(true)
        return observeTransactions()
                .doOnSubscribe { disp: Disposable ->
                    mGattDisposable.add(disp)
                }
    }

    /**
     * @return a completsable that completes when the current transaction is finished, with optional error state
     */
    override fun awaitTransaction(): Completable {
        return Completable.mergeArray(
                transactionCompleteRelay.firstOrError().ignoreElement(),
                transactionErrorRelay.flatMapCompletable { error: Throwable? -> Completable.error(error) }
        )
    }

    /**
     * @return observable with transaction stats
     */
    override fun observeTransactions(): Observable<HandshakeResult> {
        return Observable.merge(
                transactionCompleteRelay,
                transactionErrorRelay.flatMap { exception: Throwable -> throw exception }
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

    /**
     * starts the gatt server in the background.
     * NOTE: this function contains all the logic for running the state machine.
     * this function NEEDS to be called for the device to be connectable
     * @return false on failure
     */
    override fun startServer(): Boolean {
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
        val d = mServer.openServer(config)
                .doOnSubscribe { Log.v(TAG, "gatt server subscribed") }
                .doOnError { Log.e(TAG, "failed to open server") }
                .concatMap { connectionRaw: RxBleServerConnection ->
                    // wrap connection in CachedLeServiceConnection for convenience
                    val connection = CachedLEServerConnection(connectionRaw, channels)

                    // gatt server library doesn't give us a handle to RxBleDevice so we look it up manually
                    val device = mClient.getBleDevice(connection.connection.device.address)

                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.connection.device.address + " was null in client")
                        return@concatMap Observable.error<BootstrapRequest>(IllegalStateException("device was null"))
                    }
                    //accquire wakelock
                    acquireWakelock()
                    Log.d(TAG, "gatt server connection from " + connectionRaw.device.address)

                    initializeProtocol(connection.connection.device)
                            .flatMapObservable { session: LeDeviceSession ->
                                Observable.just(establishConnection(device, Timeout(CLIENT_CONNECT_TIMEOUT.toLong(), TimeUnit.SECONDS)))
                                        .flatMap { clientConnection: CachedLEConnection ->
                                            Log.v(TAG, "stating stages")
                                            /*
                                             * this is kind of hacky. this observable chain is the driving force of our state machine
                                             * state transitions are handled when our LeDeviceSession is subscribed to.
                                             * each transition causes the session to emit the next state and start over
                                             * since states are only handled on subscibe or on a terminal error, there
                                             * shouldn't be any races or reentrancy issues
                                             */
                                            session.observeStage()
                                                    .doOnNext { stage: String? -> Log.v(TAG, "handling stage: $stage") }
                                                    .flatMapSingle {
                                                        Single.zip(
                                                                session.singleClient(),
                                                                session.singleServer(),
                                                                { client: ClientTransaction, server: ServerTransaction ->
                                                                    server(connection)
                                                                            .doOnSuccess { Log.v(TAG, "server handshake completed") }
                                                                            .zipWith(
                                                                                    client(clientConnection), { first: Optional<BootstrapRequest>, second: TransactionResult<BootstrapRequest> -> android.util.Pair(first, second) })
                                                                            .toObservable()
                                                                            .doOnSubscribe { Log.v("debug", "client handshake subscribed") }
                                                                }
                                                        )
                                                    }
                                                    .concatMap { result: Observable<android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>>> -> result }
                                                    .doOnNext { transactionResult ->
                                                        session.stage = transactionResult.second.nextStage
                                                        if (transactionResult.second.nextStage == TransactionResult.STAGE_EXIT &&
                                                                !transactionResult.second.err) {
                                                            connection.dispose()
                                                            connectionRaw.disconnect()
                                                            removeConnection(device.macAddress)
                                                        }
                                                    }
                                                    .takeUntil { result: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> result.second.nextStage == TransactionResult.STAGE_EXIT }
                                        }
                                        .takeUntil { result: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> result.second.nextStage == TransactionResult.STAGE_EXIT }
                                        .filter { pair: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> pair.second.hasResult() || pair.first.isPresent }
                                        .map { pair: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> ->
                                            when {
                                                pair.second.hasResult() -> {
                                                    return@map pair.second.result
                                                }
                                                pair.first.isPresent -> {
                                                    return@map pair.first.get()
                                                }
                                                else -> {
                                                    throw IllegalStateException("this should never happen")
                                                }
                                            }
                                        }
                                        .doOnError { err: Throwable ->
                                            Log.e(TAG, "stage " + session.stage + " error " + err)
                                            err.printStackTrace()
                                        }
                                        .onErrorResumeNext(Observable.never())
                            }
                }
                .doOnDispose {
                    Log.e(TAG, "gatt server disposed")
                    transactionErrorRelay.accept(IllegalStateException("gatt server disposed"))
                    stopServer()
                }
                .flatMapSingle { bootstrapRequest: BootstrapRequest -> bootstrapWifiP2p(bootstrapRequest) }
                .doOnError { err: Throwable ->
                    Log.e(TAG, "gatt server shut down with error: $err")
                    err.printStackTrace()
                }
                .retry()
                .repeat()
                .doOnNext { releaseWakeLock() }
                .subscribe(transactionCompleteRelay)
        mGattDisposable.add(d)
        startAdvertise()
        return true
    }

    /**
     * stop our server and stop advertising
     */
    override fun stopServer() {
        mServer.closeServer()
        stopAdvertise()
    }

    /**
     * implent a locking mechanism to grant single devices tempoary
     * exclusive access to channel characteristics
     */
    class LockedCharactersitic(@get:Synchronized
                               val characteristic: BluetoothGattCharacteristic, val channel: Int) {
        private val lockState = BehaviorRelay.create<Boolean>()
        fun awaitCharacteristic(): Single<OwnedCharacteristic> {
            return Single.just(asUnlocked())
                    .zipWith(lockState.filter { p: Boolean? -> !p!! }.firstOrError(), { ch: OwnedCharacteristic?, _: Boolean? -> ch!! })
                    .map { ch: OwnedCharacteristic? ->
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