package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.os.ParcelUuid
import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.Timeout
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.LUID_DATA
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import net.ballmerlabs.uscatterbrain.util.toUuid
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val ioScheduler: Scheduler,
    private val firebase: FirebaseWrapper,
    private val scheduler: Provider<ScatterbrainScheduler>,
    private val newServer: GattServer,
    private val advertiser: Advertiser,

    ) : LeState {
    private val transactionLock: AtomicReference<UUID?> = AtomicReference<UUID?>(null)
    private val transactionInProgress: AtomicInteger = AtomicInteger(0)

    override val createGroupCache: AtomicReference<BehaviorSubject<WifiDirectBootstrapRequest>> =  AtomicReference<BehaviorSubject<WifiDirectBootstrapRequest>>()

    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()
    override val connectionCache: ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent> =
        ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent>()
    private val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()
    private val votingLockObs = AtomicReference<CompletableSubject>(null)

    // a "channel" is a characteristic that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic> =
        ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>()
    private val LOG by scatterLog()

    private val server = AtomicReference<Pair<GattServerConnectionSubcomponent, Disposable>?>(null)


    private fun forget(serverConnnection: GattServerConnection): Completable {
        return serverConnnection.getEvents()
            .filter{ p -> p.uuid == BluetoothLERadioModuleImpl.UUID_FORGET && p.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE }
            .flatMapCompletable { trans ->
                val luid = BluetoothLERadioModuleImpl.bytes2uuid(trans.value)
                if (luid != null && connectionCache[luid] != null) {
                    LOG.w("remote peer $luid reset luid")
                    trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                        .delay(10, TimeUnit.SECONDS, ioScheduler)
                        .andThen(Completable.fromAction {
                            updateDisconnected(luid)
                        })
                } else {
                    LOG.w("remote peer tried to reset nonexistent luid $luid")
                    trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_FAILURE)
                }
            }.onErrorComplete()
    }

    private fun helloRead(serverConnection: GattServerConnection): Completable {
        return serverConnection.getEvents()
            .filter { p -> p.uuid == BluetoothLERadioModuleImpl.UUID_HELLO && p.operation == GattServerConnection.Operation.CHARACTERISTIC_READ }
            .doOnSubscribe { LOG.v("hello characteristic read subscribed") }
            .flatMapCompletable { trans ->
                val luid = advertiser.getHashLuid()
                LOG.v("hello characteristic read, replying with luid $luid")
                trans.sendReply(
                    BluetoothLERadioModuleImpl.uuid2bytes(luid),
                    BluetoothGatt.GATT_SUCCESS
                )
            }
            .doOnError { err ->
                LOG.e("error in hello characteristic read: $err")
            }.onErrorComplete()
            .repeat()
            .retry()

    }

    override fun disconnectServer(device: RxBleDevice?) {
        if (device != null) {
            server.get()?.first?.connection()?.disconnect(device)
        }
    }

    override fun stopServer() {
        val s = server.getAndSet(null)
        s?.first?.cachedConnection()?.dispose()
        s?.second?.dispose()
    }

    private fun helloWrite(serverConnection: CachedLeServerConnection): Observable<HandshakeResult> {
        return serverConnection.connection.getEvents()
            .filter { p -> p.uuid == BluetoothLERadioModuleImpl.UUID_HELLO && p.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE }
            .flatMapMaybe { trans ->
                scheduler.get().acquireWakelock()
                LOG.e("hello from ${trans.remoteDevice.macAddress}")
                val luid = BluetoothLERadioModuleImpl.bytes2uuid(trans.value)!!
                updateActive(luid)
                serverConnection.connection.setOnDisconnect(trans.remoteDevice) {
                    LOG.e("server onDisconnect $luid")
                    updateDisconnected(luid)
                    serverConnection.unlockLuid(luid)
                }

                LOG.v("server handling luid $luid")
                LOG.v("transaction NOT locked, continuing")


                establishConnectionCached(trans.remoteDevice, luid)
                    .flatMapMaybe { connection ->

                        LOG.e("this is a reverse connection")
                        trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                            .andThen(
                                connection.bluetoothLeRadioModule()
                                    .handleConnection(luid)
                            )
                    }
                    .onErrorComplete()
                    .doFinally {
                        transactionUnlock(luid)
                    }
                    .doOnError { err ->
                        LOG.e("error in handleConnection $err")
                        firebase.recordException(err)
                        //   updateDisconnected(luid)
                    }


            }
            .onErrorReturnItem(
                HandshakeResult(
                    0,
                    0,
                    HandshakeResult.TransactionStatus.STATUS_FAIL
                )
            )
            .repeat()
            .retry()
            .doOnError { e ->
                LOG.e("failed to read hello characteristic: $e")
            }
            .doOnNext { t -> LOG.v("transactionResult ${t.success}") }
    }

    override fun getServerSync(): GattServerConnectionSubcomponent? {
        return server.get()?.first
    }

    /**
     * starts the gatt server in the background.
     * NOTE: this function contains all the LOGic for running the state machine.
     * this function NEEDS to be called for the device to be connectable
     * @return false on failure
     */
    override fun startServer(): Completable {
        // initialize our channels
        channels.clear()
        makeCharacteristic(BluetoothLERadioModuleImpl.UUID_SEMAPHOR)
        makeCharacteristic(BluetoothLERadioModuleImpl.UUID_HELLO)
        makeCharacteristic(BluetoothLERadioModuleImpl.UUID_FORGET)
        setupChannels()
        val config = ServerConfig(operationTimeout = Timeout(5, TimeUnit.SECONDS))
            .addService(gattService)

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
        return newServer.openServer(config)
            .doOnSubscribe { LOG.v("gatt server subscribed") }
            .doOnError { e ->
                LOG.e("failed to open server: $e")
            }
            .flatMapCompletable { connectionRaw ->
                Completable.fromAction {
                    LOG.v("gatt server initialized")


                    val write = helloWrite(connectionRaw.cachedConnection())
                    val read = helloRead(connectionRaw.connection())
                    val forget = forget(connectionRaw.connection())

                    val disp = write
                        .mergeWith(read)
                        .mergeWith(forget)
                        .ignoreElements()
                        .subscribe(
                            { LOG.e("server handler completed") },
                            { err -> LOG.e("server handler error $err") }
                        )

                    val old = server.getAndSet(Pair(connectionRaw, disp))
                    old?.first?.cachedConnection()?.dispose()
                    old?.second?.dispose()
                }
            }
    }

    override fun getServer(): Maybe<GattServerConnectionSubcomponent> {
        return Maybe.defer {
            val s = server.get()
            if (s != null) {
                Maybe.just(s.first)
            } else {
                Maybe.empty()
            }
        }
    }
    

    override fun transactionLockIsSelf(luid: UUID?): Boolean {
        val lock = transactionLock.get()
        return lock != null && lock == luid
    }

    override fun votingLock(): Completable {
        return Completable.defer {
            votingLockObs.getAndUpdate { v ->
                when (v) {
                    null -> CompletableSubject.create()
                    else -> v
                }
            } ?: Completable.complete()
        }.doOnSubscribe { LOG.w("subscribed voting lock") }
            .doFinally { LOG.w("voting lock complete") }
    }

    override fun votingUnlock() {
        val obs = votingLockObs.getAndSet(null)
        obs?.onComplete()
    }

    override fun startTransaction(): Int {
        return transactionInProgress.incrementAndGet()
    }

    override fun stopTransaction(): Int {
        return transactionInProgress.updateAndGet { v ->
            when (v) {
                0 -> 0
                else -> v - 1
            }
        }
    }

    override fun shouldConnect(luid: UUID): Boolean {
        return !activeLuids.containsKey(luid)
    }

    override fun transactionLockAccquire(luid: UUID?): Boolean {
        val lock = transactionLock.getAndAccumulate(luid) { c, n ->
            when (c) {
                n -> n
                null -> n
                else -> c
            }
        }
        return lock == null || lock != luid
    }

    override fun transactionUnlock(luid: UUID): Boolean {
        LOG.e("transactionUnlock ${transactionLock.get()} $luid")
        return transactionLock.accumulateAndGet(luid) { old, new ->
            if (old?.equals(new) == true) {
                null
            } else {
                old
            }
        } == null
    }

    override fun updateActive(uuid: UUID?): Boolean {
        return if (uuid != null) activeLuids.put(uuid, true) == null else false
    }

    override fun updateActive(scanResult: ScanResult): Boolean {
        return updateActive(getAdvertisedLuid(scanResult))
    }

    override fun updateDisconnected(luid: UUID) {
        val c = connectionCache.remove(luid)
        LOG.e("updateDisconnected $luid ${c?.connection()}")
        c?.connection()?.dispose()
        val device = c?.device()
        if (device != null) {
            //  server.get()?.disconnect(device)
            getServerSync()?.cachedConnection()?.unlockLuid(luid)
            if (connectionCache.size == 0) {
                getServerSync()?.connection()?.resetMtu(device.macAddress)
            }
        }

        if (advertiser.checkForget(luid)) {
           activeLuids.remove(luid)
        }
        transactionLock.set(null)

    }

    override fun updateGone(luid: UUID) {
        advertiser.forget(luid)
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null
                && !activeLuids.containsKey(advertisingLuid)
    }

    override fun activeCount(): Int {
        return activeLuids.size
    }

    override fun processScanResult(
        remoteUuid: UUID,
        device: RxBleDevice
    ): Maybe<HandshakeResult> {
        return Maybe.defer {
            establishConnectionCached(device, remoteUuid)
                .flatMapMaybe { cached ->
                    cached.bluetoothLeRadioModule().initiateOutgoingConnection(
                        remoteUuid
                    ).onErrorComplete()
                        .onErrorComplete()
                }
        }
    }


    override fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return scanResult.scanRecord.serviceData[ParcelUuid(LUID_DATA)]?.toUuid()
    }

    override fun dumpPeers(): Completable {
        return Completable.fromAction {
            val entries = connectionCache.entries
            for ((luid, connection) in entries) {
                updateGone(luid)
                connection.connection().dispose()
            }
            connectionCache.clear()
        }
    }

    override fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID
    ): Single<ScatterbrainTransactionSubcomponent> {
        val connectSingle =
            Single.defer {
                val connection = connectionCache[luid]
                if (connection != null) {
                    LOG.e("establishing cached connection to ${device.macAddress} ${device.name}, $luid, ${connectionCache.size} devices connected")
                    connection.connection().connection
                        .firstOrError()
                        .ignoreElement()
                        .toSingleDefault(connection)
                } else {
                    LOG.e("establishing NEW connection to ${device.macAddress} ${device.name}, $luid, ${connectionCache.size} devices connected")
                    val newconnection = getServerSync()!!.factory().transaction(device, luid)
                    getServerSync()!!.connection().setOnMtuChanged(device.bluetoothDevice) { m ->
                        newconnection.connection().mtu.getAndUpdate { v -> if (m < v)
                            m
                         else
                            v
                        }
                    }
                    val rawConnection = device.establishConnection(false)
                        .flatMapSingle { v ->
                            v.discoverServices()
                                .flatMap {
                                    v.requestMtu(512)
                                        .map { m ->
                                            newconnection.connection().mtu.getAndUpdate { v -> if (m < v)
                                                m
                                            else
                                                v
                                            }
                                            LOG.w("actual mtu $m")
                                            server.get()?.first?.connection()?.forceMtu(device.macAddress, m)
                                            v
                                        }
                                }.timeout(60, TimeUnit.SECONDS)
                        }
                        .doOnNext {
                            LOG.d("now connected ${device.macAddress}")
                        }.doOnError { err ->
                            if (err is TimeoutException) {
                                LOG.w("connect timed out, updating gone")
                                updateGone(luid)
                            }
                            LOG.w("connection error $err, updating disconnected")
                            updateDisconnected(luid)
                        }

                    newconnection.connection().subscribeConnection(rawConnection)
                    connectionCache[luid] = newconnection
                    //  updateActive(luid)
                    newconnection.connection().setOnDisconnect {
                        LOG.e("client onDisconnect $luid")
                        updateDisconnected(luid)
                        newconnection.bluetoothLeRadioModule().cancelTransaction()
                        if (connectionCache.isEmpty()) {
                            // LOG.e("connectionCache empty, removing luid")
                            // advertiser.removeLuid()
                            Completable.complete()
                        } else {
                            Completable.complete()
                        }

                    }
                    newconnection.connection().connection.firstOrError().ignoreElement()
                        .toSingleDefault(newconnection)
                }
            }

        return connectSingle
            .doOnError { updateGone(luid) }
    }

    override fun refreshPeers(): Completable {
        return Observable.fromIterable(connectionCache.entries).flatMapCompletable { v ->
            v.value.bluetoothLeRadioModule().initiateOutgoingConnection(v.key)
                .ignoreElement()
                .onErrorComplete()
        }.andThen(Completable.fromAction {
            LOG.v("refreshPeers called")
            activeLuids.clear()
        })
    }


    init {
        refreshInProgresss.accept(false)
    }


}