package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGatt
import android.os.ParcelUuid
import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.RxBlePhy
import com.polidea.rxandroidble2.RxBlePhyOption
import com.polidea.rxandroidble2.Timeout
import com.polidea.rxandroidble2.exceptions.BleAlreadyConnectedException
import com.polidea.rxandroidble2.exceptions.BleDisconnectedException
import com.polidea.rxandroidble2.exceptions.BleGattCallbackTimeoutException
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
import net.ballmerlabs.scatterproto.bytes2uuid
import net.ballmerlabs.scatterproto.uuid2bytes
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.LUID_DATA
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.Companion.UUID_REVERSE
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import net.ballmerlabs.uscatterbrain.util.toBytes
import net.ballmerlabs.uscatterbrain.util.toUuid
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.Throws

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) private val connectScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.TIMEOUT) private val timeoutScheduler: Scheduler,
    private val firebase: FirebaseWrapper,
    private val scheduler: Provider<ScatterbrainScheduler>,
    private val newServer: GattServer,
    private val advertiser: Advertiser,
    private val broadcastReceiverState: BroadcastReceiverState,
    private val wifiDirectBroadcastReceiver: WifiDirectBroadcastReceiver
) : LeState {
    private val transactionInProgress: AtomicInteger = AtomicInteger(0)
    private val connectionSubject = BehaviorSubject.create<UUID>()
    private val disconnectRelay = BehaviorRelay.create<RxBleDevice>()


    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()
    private val transactionCache: ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent> =
        ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent>()
    private val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()

    // a "channel" is a characteristic that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic> =
        ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>()
    private val LOG by scatterLog()

    private val server =
        BehaviorSubject.create<AtomicReference<Pair<GattServerConnectionSubcomponent, Disposable>?>>()

    override fun connection(): List<ScatterbrainTransactionSubcomponent> {
        return transactionCache.values.toList()
    }

    private fun forget(serverConnnection: GattServerConnection): Completable {
        return serverConnnection.getEvents()
            .filter { p -> p.uuid == BluetoothLERadioModuleImpl.UUID_FORGET && p.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE }
            .flatMapCompletable { trans ->
                val luid = bytes2uuid(trans.value)
                val c = transactionCache[luid]
                if (luid != null && c != null) {
                    LOG.w("remote peer $luid reset luid")
                    wifiDirectBroadcastReceiver.removeCurrentGroup()
                        .onErrorComplete()
                        .andThen(
                            trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                                .andThen(Completable.fromAction {
                                    updateGone(luid, IllegalStateException("luid reset"))
                                    c.bluetoothLeRadioModule().cancelTransaction()
                                })
                        )
                } else {
                    LOG.w("remote peer tried to reset nonexistent luid $luid GATT_FAILURE")
                    trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
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
                    uuid2bytes(luid), BluetoothGatt.GATT_SUCCESS
                )
            }.doOnError { err ->
                LOG.e("error in hello characteristic read: $err")
            }.onErrorComplete().repeat().retry()

    }


    override fun stopServer(): Completable {
        //dumpPeers(false).blockingAwait()

        return server.firstElement().flatMapCompletable { se ->
            val s = se.getAndSet(null)
            s?.first?.cachedConnection()?.dispose()
            s?.second?.dispose()
            Completable.complete()
        }

    }

    override fun updateDisconnected(luid: UUID, reason: String) {
        transactionCache[luid]?.connection()?.disconnect()
    }

    private fun reverseWrite(serverConnection: CachedLeServerConnection): Observable<HandshakeResult> {
        return serverConnection.connection.getEvents()
            .filter { p -> p.uuid == BluetoothLERadioModuleImpl.UUID_REVERSE && p.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE }
            .flatMapMaybe { trans ->
                scheduler.get().acquireWakelock()
                LOG.e("hello from ${trans.remoteDevice.macAddress}")
                val luid = bytes2uuid(trans.value)!!
                updateActive(luid)
                serverConnection.connection.setOnDisconnect(trans.remoteDevice.macAddress) {
                    LOG.e("server onDisconnect $luid")
                    cleanupConnection(trans.remoteDevice.macAddress, luid, true)
                    serverConnection.unlockLuid(luid)
                }

                LOG.v("server handling luid $luid")
                LOG.v("transaction NOT locked, continuing")

                trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                    .andThen(establishConnectionCached(trans.remoteDevice, luid, reverse = true))
                    .flatMapMaybe { connection ->
                        connection.bluetoothLeRadioModule().handleConnection(luid, reverse = true)
                    }.doOnError { err ->
                        LOG.e("error in handleConnection $err")
                        firebase.recordException(err)
                        //   updateDisconnected(luid)
                    }


            }.onErrorReturnItem(
                HandshakeResult(
                    0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL
                )
            ).repeat().retry().doOnError { e ->
                LOG.e("failed to read hello characteristic: $e")
            }.doOnNext { t -> LOG.v("transactionResult ${t.success}") }
    }

    private fun helloWrite(serverConnection: CachedLeServerConnection): Observable<HandshakeResult> {
        return serverConnection.connection.getEvents()
            .filter { p -> p.uuid == BluetoothLERadioModuleImpl.UUID_HELLO && p.operation == GattServerConnection.Operation.CHARACTERISTIC_WRITE }
            .flatMapMaybe { trans ->
                scheduler.get().acquireWakelock()
                LOG.e("hello from ${trans.remoteDevice.macAddress}")
                val luid = bytes2uuid(trans.value)!!
                updateActive(luid)
                serverConnection.connection.setOnDisconnect(trans.remoteDevice.macAddress) {
                    LOG.e("server onDisconnect $luid")
                    cleanupConnection(trans.remoteDevice.macAddress, luid, true)
                    serverConnection.unlockLuid(luid)
                }

                LOG.v("server handling luid $luid")
                LOG.v("transaction NOT locked, continuing")

                trans.sendReply(byteArrayOf(), BluetoothGatt.GATT_SUCCESS)
                    .andThen(establishConnectionCached(trans.remoteDevice, luid, reverse = false))
                    .flatMapMaybe { connection ->
                        LOG.e("this is a reverse connection")
                        connection.connection().connection.firstOrError().flatMapMaybe { v ->
                            v.writeCharacteristic(UUID_REVERSE, advertiser.getHashLuid().toBytes())
                                .flatMapMaybe {
                                    connection.bluetoothLeRadioModule()
                                        .handleConnection(luid, reverse = false)
                                }
                        }

                    }.onErrorComplete().doOnError { err ->
                        LOG.e("error in handleConnection $err")
                        firebase.recordException(err)
                        //   updateDisconnected(luid)
                    }


            }.onErrorReturnItem(
                HandshakeResult(
                    0, 0, HandshakeResult.TransactionStatus.STATUS_FAIL
                )
            ).repeat().retry().doOnError { e ->
                LOG.e("failed to read hello characteristic: $e")
            }.doOnNext { t -> LOG.v("transactionResult ${t.success}") }
    }

    override fun getServerSync(): GattServerConnectionSubcomponent? {
        return server.value?.get()?.first
    }

    /**
     * starts the gatt server in the background.
     * NOTE: this function contains all the LOGic for running the state machine.
     * this function NEEDS to be called for the device to be connectable
     * @return false on failure
     */
    override fun startServer(): Completable {
        val config =
            ServerConfig(operationTimeout = Timeout(5, TimeUnit.SECONDS)).addService(gattService)

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
        return stopServer().andThen(
            newServer.openServer(config)
                .doOnSubscribe { LOG.v("gatt server subscribed") }
        )

            .flatMapCompletable { connectionRaw ->
                server.firstOrError().flatMapCompletable { s ->
                    LOG.v("gatt server initialized")


                    val write = helloWrite(connectionRaw.cachedConnection())
                    val read = helloRead(connectionRaw.connection())
                    val forget = forget(connectionRaw.connection())
                    val reverse = reverseWrite(connectionRaw.cachedConnection())

                    val disp =
                        write.mergeWith(read).mergeWith(forget).mergeWith(reverse).ignoreElements()
                            .subscribe({ LOG.e("server handler completed") },
                                { err -> LOG.e("server handler error $err") })

                    val old = s.getAndSet(Pair(connectionRaw, disp))
                    old?.first?.connection()?.dispose()
                    old?.second?.dispose()
                    Completable.complete()

                }
            }.doOnError { e ->
                LOG.e("failed to open server: $e")
            }
    }

    override fun getServer(): Single<GattServerConnectionSubcomponent> {
        return server.filter { v -> v.get() != null }.map { v -> v.get()!!.first }.firstOrError()
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

    override fun updateActive(uuid: UUID?): Boolean {
        return if (uuid != null) activeLuids.put(uuid, true) == null else false
    }

    override fun updateActive(scanResult: ScanResult): Boolean {
        return updateActive(getAdvertisedLuid(scanResult))
    }

    private fun cleanupConnection(mac: String, luid: UUID, dispose: Boolean) {
        broadcastReceiverState.killBatch(luid)
        val c = transactionCache.remove(luid)
        c?.bluetoothLeRadioModule()?.cancelTransaction()
        if (dispose) {
            c?.connection()?.disconnect()
        }
        val device = c?.device()
        if (device != null) {
            getServerSync()?.connection()?.resetMtu(device.macAddress)
            if (transactionCache.size == 0) {
                getServerSync()?.connection()?.clearMtu()
            }
            try {
                if (dispose)
                    getServerSync()?.connection()?.server()
                        ?.cancelConnection(device.bluetoothDevice)
            } catch (exc: SecurityException) {
                LOG.w("failed to disconnect server $exc")
            }
        }
        getServerSync()?.cachedConnection()?.unlockLuid(luid)
        if (advertiser.checkForget(luid)) {
            activeLuids.remove(luid)
        }
        if (advertiser.checkForget(luid)) {
            activeLuids.remove(luid)
        }
    }

    override fun updateGone(luid: UUID, err: Throwable) {
        LOG.w("updateGone ")
        when (err) {
            is BleDisconnectedException -> {
                if (err.state != 0 && err.state != -1) {
                    activeLuids.remove(luid)
                }
            }
            else -> activeLuids.remove(luid)
        }
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null && !activeLuids.containsKey(advertisingLuid)
    }

    override fun activeCount(): Int {
        return activeLuids.size
    }

    override fun processScanResult(
        remoteUuid: UUID, device: RxBleDevice
    ): Completable {
        return establishConnectionCached(device, remoteUuid).flatMapCompletable { cached ->
                cached.bluetoothLeRadioModule().initiateOutgoingConnection(
                    remoteUuid
                )
            }
    }


    override fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return scanResult.scanRecord.serviceData[ParcelUuid(LUID_DATA)]?.toUuid()
    }

    override fun dumpPeers(forget: Boolean): Completable {
        return Completable.fromAction {
            val entries = transactionCache.entries
            for ((luid, connection) in entries) {
                connection.connection().disconnect()
            }
            transactionCache.clear()
            getServerSync()?.connection()?.clearMtu()
            if (forget) activeLuids.clear()
        }
    }

    private fun setupConnection(
        connection: RxBleConnection, luid: UUID, reverse: Boolean
    ): Completable {
        return Completable.defer {
            if (!reverse) {/*
                val phy = if (bluetoothManager.adapter.isLe2MPhySupported)
                    setOf(RxBlePhy.PHY_2M)
                else
                    setOf(RxBlePhy.PHY_1M)

                 */
                val phy = setOf(RxBlePhy.PHY_2M, RxBlePhy.PHY_1M, RxBlePhy.PHY_CODED)
                connection.setPreferredPhy(phy, phy, RxBlePhyOption.PHY_OPTION_NO_PREFERRED)
                    .ignoreElement().doOnError { err -> LOG.e("failed to set phy $err") }
                    .onErrorComplete().andThen(connection.requestMtu(512 - 3)
                        .doOnError { err -> LOG.e("failed to set mtu $err") })
                    .flatMapCompletable { m ->
                        getServer().flatMapCompletable { server ->
                            Completable.fromAction {
                                LOG.w("actual mtu $m")
                                server.cachedConnection().mtu.set(m)
                            }
                        }

                            .andThen(
                                connection.discoverServices().ignoreElement()
                            )
                    }.onErrorComplete()
            } else {
                Completable.complete()
            }
        }
    }

    /*
    override fun cry(exception: Throwable): Completable {
        return when (exception) {
            is BleDisconnectedException -> {
                if (exception.state == 133) {
                    LOG.cry("IM A SAD PUDDLE, ${exception.state} ERROR. NUKING EVERYTHING SORRRYYY")
                    return dumpPeers(true).onErrorComplete()
                      .andThen(Completable.error(exception))
                } else {
                    Completable.error(exception)
                }
            }

            else -> Completable.error(exception)
        }
    }
     */

    override fun observeDisconnects(): Observable<RxBleDevice> {
        return disconnectRelay
    }

    override fun establishConnectionCached(
        device: RxBleDevice, luid: UUID, reverse: Boolean
    ): Single<ScatterbrainTransactionSubcomponent> {
        val connectSingle = getServer().flatMap { s ->
            val c = transactionCache[luid]

            val res = when (c) {
                null -> {
                    LOG.e("establishing NEW connection to ${device.macAddress} $reverse, $luid, ${transactionCache.size} devices connected")
                    val newconnection = s.transaction().device(device).luid(luid).build()!!
                    transactionCache[luid] = newconnection
                    //  connectionQueue.add(luid)


                    /*
                    if (connectionQueue.size > 4) {
                        val removeLuid = connectionQueue.remove()
                        LOG.w("connection cache full, removing $removeLuid from cache")
                        updateDisconnected(removeLuid, "exceeded current connection limit")
                    }

                     */

                    val rawConnection =
                        Completable.fromAction {
                            scheduler.get().pauseScan()
                            advertiser.setBusy(true)
                        }.andThen(Completable.timer(200, TimeUnit.MILLISECONDS, connectScheduler))
                            .andThen(
                                device.establishConnection(false, Timeout(30, TimeUnit.SECONDS))
                                    .subscribeOn(connectScheduler)
                                    .observeOn(newconnection.bleParse())
                                    .doOnError { err ->
                                        when (err) {
                                            is BleDisconnectedException -> {
                                                when (err.state) {
                                                    0 -> LOG.v("device disconnected")
                                                    -1 -> LOG.v("remote peer terminated connection")
                                                    else -> {
                                                        updateGone(luid, err)
                                                        LOG.w("BLE client error $err")
                                                    }
                                                }
                                            }

                                            else -> LOG.w("BLE client error $err")
                                        }
                                    }.flatMapSingle { c ->
                                        setupConnection(c, luid, reverse).toSingleDefault(c)
                                    }
                            )
                            .concatWith(Completable.timer(5, TimeUnit.SECONDS, connectScheduler))
                            .onErrorResumeNext { err: Throwable ->
                                Completable.timer(5, TimeUnit.SECONDS, connectScheduler)
                                    .andThen(Observable.error(err))
                            }
                            .doFinally {
                                LOG.e("client onDisconnect $luid")
                                newconnection.bluetoothLeRadioModule().cancelTransaction()
                                disconnectRelay.accept(device)
                                advertiser.setBusy(false)
                                cleanupConnection(device.macAddress, luid, false)
                                scheduler.get().unpauseScan()
                            }
                            .doOnNext {
                                scheduler.get().unpauseScan()
                                advertiser.setBusy(false)
                                LOG.d("now connected ${device.macAddress}")
                            }


                    newconnection.connection().subscribeConnection(rawConnection)
                    /*
                    s.connection().setOnDisconnect(device.macAddress) {
                        LOG.w("client handled server disconnection ${device.macAddress}")
                        newconnection.connection().disconnect()
                    }
                     */
                    newconnection
                }

                else -> {
                    LOG.w("establishing cached connection to ${device.macAddress} $reverse, $luid, ${transactionCache.size} devices connected")
                    c
                }
            }
            res.connection().connection.firstOrError()
                .timeout(30, TimeUnit.SECONDS, timeoutScheduler)
                .ignoreElement().toSingleDefault(res)
        }

        val connectWait = if (reverse)
            connectionSubject.takeUntil { v -> v == luid }.ignoreElements()
        else
            Completable.complete()

        return connectSingle.doOnSubscribe { connectionSubject.onNext(luid) }
            .doFinally { advertiser.setBusy(false) }


    }

    override fun refreshPeers(): Completable {
        return Observable.fromIterable(transactionCache.entries).flatMapCompletable { v ->
            v.value.bluetoothLeRadioModule().initiateOutgoingConnection(v.value.luid())
                .onErrorComplete()
        }.andThen(Completable.fromAction {
            LOG.v("refreshPeers called")
            scheduler.get().pauseScan()
            activeLuids.clear()
        })
            .andThen(Completable.timer(2, TimeUnit.SECONDS, timeoutScheduler))
            .doFinally {
                scheduler.get().unpauseScan()
            }
    }


    init {
        server.onNext(AtomicReference(null))
        setupChannels()
        refreshInProgresss.accept(false)
    }


}