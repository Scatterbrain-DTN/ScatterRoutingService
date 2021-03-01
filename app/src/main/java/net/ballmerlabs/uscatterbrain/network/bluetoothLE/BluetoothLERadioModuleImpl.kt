package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
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
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.ConnectionRole
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.discoveryOptions
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
        const val NUM_CHANNELS = 8
        val SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90")
        val UUID_SEMAPHOR = UUID.fromString("3429e76d-242a-4966-b4b3-301f28ac3ef2")
        const val WAKE_LOCK_TAG = "net.ballmerlabs.uscatterbrain::scatterbrainwakelock"
        val channels = ConcurrentHashMap<UUID, LockedCharactersitic>()
        val mService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
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

        fun makeCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
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
            makeCharacteristic(UUID_SEMAPHOR)
            for (i in 0 until NUM_CHANNELS) {
                val channel = incrementUUID(SERVICE_UUID, i + 1)
                channels[channel] = LockedCharactersitic(makeCharacteristic(channel), i)
            }
        }
    }

    private val mGattDisposable = CompositeDisposable()
    private val discoverDelay = 45
    private val discoveryDispoable = AtomicReference<Disposable>()
    private val connectionCache = ConcurrentHashMap<String, Observable<CachedLEConnection>>()
    private val transactionCompleteRelay = PublishRelay.create<HandshakeResult>()
    private val connectedLuids = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
    private val myLuid = AtomicReference(UUID.randomUUID())
    private val transactionErrorRelay = PublishRelay.create<Throwable>()
    private val mAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.v(TAG, "successfully started advertise")
            val bm = mContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "failed to start advertise")
        }
    }

    private fun observeTransactionComplete() {
        val d = transactionCompleteRelay.subscribe(
                { _: HandshakeResult ->
                    Log.v(TAG, "transaction complete, randomizing luid")
                    releaseWakeLock()
                    myLuid.set(UUID.randomUUID())
                }
        ) { err: Throwable -> Log.e(TAG, "error in transactionCompleteRelay $err") }
        mGattDisposable.add(d)
    }

    protected fun acquireWakelock() {
        wakeLock.updateAndGet {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mContext.getString(R.string.wakelock_tag))
                    .apply { acquire(10*60*1000) }
        }
    }

    protected fun releaseWakeLock() {
        wakeLock.getAndUpdate { null }?.release()
    }

    private val powerSave: String?
        get() = preferences.getString(
                mContext.getString(R.string.pref_powersave),
                mContext.getString(R.string.powersave_active)
        )

    private fun parseScanMode(): Int {
        val scanMode = powerSave
        return if (scanMode == mContext.getString(R.string.powersave_active)) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else if (scanMode == mContext.getString(R.string.powersave_opportunistic)) {
            ScanSettings.SCAN_MODE_OPPORTUNISTIC
        } else {
            -1 //scan disabled
        }
    }

    override fun startAdvertise() {
        Log.v(TAG, "Starting LE advertise")
        if (Build.VERSION.SDK_INT >= 26) {
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
    }

    override fun stopAdvertise() {
        Log.v(TAG, "stopping LE advertise")
        mAdvertiser.stopAdvertising(mAdvertiseCallback)
    }

    private fun selectProvides(): AdvertisePacket.Provides {
       return if (preferences.getBoolean(mContext.getString(R.string.pref_incognito), false)) AdvertisePacket.Provides.BLE else AdvertisePacket.Provides.WIFIP2P
    }

    private fun initializeProtocol(device: BluetoothDevice): Single<LeDeviceSession> {
        Log.v(TAG, "initialize protocol")
        val session = LeDeviceSession(device, myLuid)
        session.addStage(
                TransactionResult.STAGE_LUID_HASHED,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server luid hashed stage")
                    session.luidStage.selfHashed
                            .flatMapCompletable { luidpacket: LuidPacket ->
                                session.luidStage.addPacket(luidpacket)
                                serverConn.serverNotify(luidpacket)
                            }.toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client luid hashed stage")
            conn.readLuid()
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving luid packet: $err") }
                    .observeOn(clientScheduler)
                    .map<TransactionResult<BootstrapRequest>> { luidPacket: LuidPacket ->
                        if (luidPacket.protoVersion != ScatterRoutingService.PROTO_VERSION) {
                            Log.e(TAG, "error, device connected with invalid protocol version: " + luidPacket.protoVersion)
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                        }
                        synchronized(connectedLuids) {
                            val hashUUID = luidPacket.hashAsUUID
                            if (connectedLuids.contains(hashUUID)) {
                                Log.e(TAG, "device: $device already connected")
                                return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                            } else {
                                connectedLuids.add(hashUUID)
                            }
                        }
                        Log.v(TAG, "client handshake received hashed luid packet: " + luidPacket.valCase)
                        session.luidStage.addPacket(luidPacket)
                        TransactionResult(TransactionResult.STAGE_LUID, device)
                    }
        }
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
        session.addStage(
                TransactionResult.STAGE_ADVERTISE,
                l@{ serverConn ->
                    Log.v(TAG, "gatt server advertise stage")
                    return@l serverConn.serverNotify<AdvertisePacket>(AdvertiseStage.self)
                            .toSingleDefault(Optional.empty())
                }
        ) { conn: CachedLEConnection ->
            Log.v(TAG, "gatt client advertise stage")
            conn.readAdvertise()
                    .doOnSuccess { advertisePacket: AdvertisePacket? -> Log.v(TAG, "client handshake received advertise packet") }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving advertise packet: $err") }
                    .map { advertisePacket: AdvertisePacket ->
                        session.advertiseStage.addPacket(advertisePacket)
                        TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ELECTION_HASHED, device)
                    }
        }
        session.addStage(
                TransactionResult.STAGE_ELECTION_HASHED,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server election hashed stage")
                    val packet = session.votingStage.getSelf(true, selectProvides())
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
                        TransactionResult<BootstrapRequest>(TransactionResult.STAGE_ELECTION, device)
                    }
        }
        session.addStage(
                TransactionResult.STAGE_ELECTION,
                { serverConn: CachedLEServerConnection ->
                    Log.v(TAG, "gatt server election stage")
                    session.luidStage.getSelf()
                            .flatMapCompletable { luidPacket: LuidPacket ->
                                val packet = session.votingStage.getSelf(false, selectProvides())
                                packet!!.tagLuid(luidPacket.luid)
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
                        val role: ConnectionRole
                        role = if (session.votingStage.selectSeme() == session.luidStage.luid) {
                            ConnectionRole.ROLE_SEME
                        } else {
                            ConnectionRole.ROLE_UKE
                        }
                        Log.v(TAG, "selected role: $role")
                        session.role = role
                        session.setUpgradeStage(provides)
                        if (provides == AdvertisePacket.Provides.INVALID) {
                            Log.e(TAG, "received invalid provides")
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                        } else if (provides == AdvertisePacket.Provides.BLE) {
                            //we should do everything in BLE. slowwwww ;(
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_IDENTITY, device)
                        } else if (provides == AdvertisePacket.Provides.WIFIP2P) {
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_UPGRADE, device)
                        } else {
                            return@map TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                        }
                    }
                    .doOnError { err: Throwable -> Log.e(TAG, "error while receiving packet: $err") }
                    .doOnSuccess { electLeaderPacket: TransactionResult<BootstrapRequest>? -> Log.v(TAG, "client handshake received election result") }
                    .onErrorReturn { err: Throwable? -> TransactionResult(TransactionResult.STAGE_EXIT, device) }
        }
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
                        .doOnSuccess { electLeaderPacket: UpgradePacket? -> Log.v(TAG, "client handshake received upgrade packet") }
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
                        TransactionResult<BootstrapRequest>(TransactionResult.STAGE_BLOCKDATA, device)
                    }
        }
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
                        Flowable.range(0, blockHeaderPacket.hashList.size)
                                .map {
                                    BlockDataStream(
                                            blockHeaderPacket,
                                            conn.readBlockSequence()
                                                    .repeat(blockHeaderPacket.hashList.size.toLong())
                                    )
                                }
                    }
                    .takeUntil { stream: BlockDataStream ->
                        val end = stream.headerPacket!!.isEndOfStream
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
                        TransactionResult<BootstrapRequest>(TransactionResult.STAGE_EXIT, device)
                    }
        }
        session.stage = TransactionResult.STAGE_LUID_HASHED
        return Single.just(session)
    }

    private fun serverBootstrapWifiDirect(session: LeDeviceSession, serverConn: CachedLEServerConnection): Single<Optional<BootstrapRequest>> {
        return if (session.role == ConnectionRole.ROLE_SEME) {
            session.upgradeStage!!.upgrade
                    .flatMap { upgradePacket: UpgradePacket ->
                        val request: BootstrapRequest = WifiDirectBootstrapRequest.create(
                                upgradePacket,
                                ConnectionRole.ROLE_SEME
                        )
                        serverConn.serverNotify(upgradePacket)
                                .toSingleDefault<Optional<BootstrapRequest>>(Optional.of(request))
                    }
        } else {
            Single.just<Optional<BootstrapRequest>>(Optional.empty())
        }
    }

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

    private fun establishConnection(device: RxBleDevice, timeout: Timeout): Observable<CachedLEConnection> {
        val conn = connectionCache[device.macAddress]
        if (conn != null) {
            return conn
        }
        val subject = BehaviorSubject.create<CachedLEConnection>()
        connectionCache[device.macAddress] = subject
        return device.establishConnection(false, timeout)
                .doOnDispose { connectionCache.remove(device.macAddress) }
                .doOnError { err: Throwable? -> connectionCache.remove(device.macAddress) }
                .map { d: RxBleConnection -> CachedLEConnection(d, channels) }
                .doOnNext { connection: CachedLEConnection ->
                    Log.v(TAG, "successfully established connection")
                    subject.onNext(connection)
                }
    }

    private fun discoverOnce(): Observable<DeviceConnection> {
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
                .concatMap { scanResult: ScanResult ->
                    Log.d(TAG, "scan result: " + scanResult.bleDevice.macAddress)
                    establishConnection(
                            scanResult.bleDevice,
                            Timeout(CLIENT_CONNECT_TIMEOUT.toLong(), TimeUnit.SECONDS)
                    )
                            .map { connection: CachedLEConnection? ->
                                DeviceConnection(
                                        scanResult.bleDevice.bluetoothDevice,
                                        connection
                                )
                            }
                }
                .doOnSubscribe { newValue: Disposable -> discoveryDispoable.set(newValue) }
    }

    private fun discover(timeout: Int, forever: Boolean): Observable<HandshakeResult> {
        return observeTransactions()
                .doOnSubscribe { disp: Disposable? ->
                    if (powerSave != mContext.getString(R.string.powersave_passive)) {
                        mGattDisposable.add(disp!!)
                        val d = discoverOnce()
                                .repeat()
                                .retry()
                                .doOnError { err: Throwable -> Log.e(TAG, "error with initial handshake: $err") }
                                .subscribe(
                                        { complete: DeviceConnection -> Log.v(TAG, "handshake completed: $complete") }
                                ) { err: Throwable ->
                                    Log.e(TAG, """
     handshake failed: $err
     ${Arrays.toString(err.stackTrace)}
     """.trimIndent())
                                }
                        if (!forever) {
                            val timeoutDisp = Completable.fromAction {}
                                    .delay(timeout.toLong(), TimeUnit.SECONDS)
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
                        mGattDisposable.add(d)
                    }
                }
    }

    override fun discoverWithTimeout(timeout: Int): Completable {
        return discover(timeout, false)
                .firstOrError()
                .ignoreElement()
    }

    override fun discoverForever(): Observable<HandshakeResult> {
        return discover(0, true)
    }

    override fun startDiscover(opts: discoveryOptions): Disposable {
        return discover(discoverDelay, opts == discoveryOptions.OPT_DISCOVER_FOREVER)
                .subscribe(
                        { res: HandshakeResult? -> Log.v(TAG, "discovery completed") }
                ) { err: Throwable -> Log.v(TAG, "discovery failed: $err") }
    }

    override fun awaitTransaction(): Completable {
        return Completable.mergeArray(
                transactionCompleteRelay.firstOrError().ignoreElement(),
                transactionErrorRelay.flatMapCompletable { error: Throwable? -> Completable.error(error) }
        )
    }

    override fun observeTransactions(): Observable<HandshakeResult> {
        return Observable.merge(
                transactionCompleteRelay,
                transactionErrorRelay.flatMap { exception: Throwable -> throw exception }
        )
    }

    override fun stopDiscover() {
        val d = discoveryDispoable.get()
        d?.dispose()
    }

    override fun startServer(): Boolean {
        if (mServer == null) {
            return false
        }
        val config = ServerConfig.newInstance(Timeout(5, TimeUnit.SECONDS))
                .addService(mService)
        val d = mServer.openServer(config)
                .doOnSubscribe { Log.v(TAG, "gatt server subscribed") }
                .doOnError { _: Throwable -> Log.e(TAG, "failed to open server") }
                .concatMap { connectionRaw: RxBleServerConnection ->
                    val connection = CachedLEServerConnection(connectionRaw, channels)
                    val device = mClient.getBleDevice(connection.connection.device.address)

                    //don't attempt to initiate a reverse connection when we already initiated the outgoing connection
                    if (device == null) {
                        Log.e(TAG, "device " + connection.connection.device.address + " was null in client")
                        return@concatMap Observable.error<BootstrapRequest>(IllegalStateException("device was null"))
                    }
                    acquireWakelock()
                    Log.d(TAG, "gatt server connection from " + connectionRaw.device.address)
                    initializeProtocol(connection.connection.device)
                            .flatMapObservable { session: LeDeviceSession ->
                                establishConnection(device, Timeout(CLIENT_CONNECT_TIMEOUT.toLong(), TimeUnit.SECONDS))
                                        .doOnError { err: Throwable -> Log.e(TAG, "error establishing reverse connection: $err")}
                                        .flatMap { clientConnection: CachedLEConnection ->
                                            Log.v(TAG, "stating stages")
                                            session.observeStage()
                                                    .doOnNext { stage: String? -> Log.v(TAG, "handling stage: $stage") }
                                                    .flatMapSingle {
                                                        Single.zip(
                                                                session.singleClient(),
                                                                session.singleServer(),
                                                                BiFunction { client: ClientTransaction, server: ServerTransaction ->
                                                                    server(connection)
                                                                            .doOnSuccess { Log.v(TAG, "server handshake completed") }
                                                                            .zipWith(
                                                                                    client(clientConnection), BiFunction<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>, android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>>> { first: Optional<BootstrapRequest>, second: TransactionResult<BootstrapRequest> -> android.util.Pair(first, second) })
                                                                            .toObservable()
                                                                            .doOnSubscribe { Log.v("debug", "client handshake subscribed") }
                                                                }
                                                        )
                                                    }
                                                    .concatMap { result: Observable<android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>>> -> result }
                                                    .takeUntil { result: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> result.second.nextStage == TransactionResult.STAGE_EXIT }
                                                    .doOnNext { transactionResult: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> session.stage = transactionResult.second.nextStage }
                                        }
                                        .takeUntil { result: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> result.second.nextStage == TransactionResult.STAGE_EXIT }
                                        .filter { pair: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> -> pair.second.hasResult() || pair.first.isPresent }
                                        .map { pair: android.util.Pair<Optional<BootstrapRequest>, TransactionResult<BootstrapRequest>> ->
                                            if (pair.second.hasResult()) {
                                                return@map pair.second.result
                                            } else if (pair.first.isPresent) {
                                                return@map pair.first.get()
                                            } else {
                                                throw IllegalStateException("this should never happen")
                                            }
                                        }
                                        .doOnError { err: Throwable ->
                                            Log.e(TAG, "stage " + session.stage + " error " + err)
                                            err.printStackTrace()
                                        }
                                        .onErrorResumeNext(Observable.never())
                                        .doFinally {
                                            Log.v(TAG, "stages complete, cleaning up")
                                            connection.dispose()
                                        }
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
                .doOnNext { s: HandshakeResult? -> releaseWakeLock() }
                .subscribe(transactionCompleteRelay)
        mGattDisposable.add(d)
        startAdvertise()
        return true
    }

    fun cleanup(device: RxBleDevice) {
        connectionCache.remove(device.macAddress)
    }

    override fun stopServer() {
        mServer!!.closeServer()
        stopAdvertise()
    }

    class DeviceConnection(val device: BluetoothDevice, val connection: CachedLEConnection?)

    class LockedCharactersitic(@get:Synchronized
                               val characteristic: BluetoothGattCharacteristic, val channel: Int) {
        private val lockState = BehaviorRelay.create<Boolean>()
        fun awaitCharacteristic(): Single<OwnedCharacteristic> {
            return Single.just(asUnlocked())
                    .zipWith(lockState.filter { p: Boolean? -> !p!! }.firstOrError(), BiFunction<OwnedCharacteristic, Boolean, OwnedCharacteristic?> { ch: OwnedCharacteristic?, lockstate: Boolean? -> ch!! })
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

    class OwnedCharacteristic(private val lockedCharactersitic: LockedCharactersitic) {
        private var released = false

        @Synchronized
        fun release() {
            released = true
        }

        @get:Synchronized
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