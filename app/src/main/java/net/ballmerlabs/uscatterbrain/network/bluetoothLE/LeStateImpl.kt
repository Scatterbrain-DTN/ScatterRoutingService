package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothManager
import android.os.ParcelUuid
import androidx.room.util.convertByteToUUID
import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser.Companion.LUID_DATA
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.util.scatterLog
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
import kotlin.math.min

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val ioScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT) private val connectScheduler: Scheduler,
    val factory: ScatterbrainTransactionFactory,
    private val server: Provider<ManagedGattServer>,
    private val radioModule: WifiDirectRadioModule,
    private val bluetoothManager: BluetoothManager
) : LeState {
    private val transactionLock: AtomicReference<UUID?> = AtomicReference<UUID?>(null)
    private val transactionInProgress: AtomicInteger = AtomicInteger(0)

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
            server.get()?.getServerSync()?.unlockLuid(luid)
            if (connectionCache.size == 0) {
                server.get().getServerSync()?.connection?.resetMtu(device.macAddress)
            }
        }
        transactionLock.set(null)

    }

    override fun updateGone(luid: UUID) {
        activeLuids.remove(luid)
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
        val res = scanResult.scanRecord.serviceData[ParcelUuid(LUID_DATA)]
        return if (res != null)
            convertByteToUUID(res)
        else
            null
    }

    override fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID
    ): Single<ScatterbrainTransactionSubcomponent> {
        val connectSingle =
            Single.fromCallable {
                val connection = connectionCache[luid]
                if (connection != null) {
                    LOG.e("establishing cached connection to ${device.macAddress} ${device.name}, $luid, ${connectionCache.size} devices connected")
                    connection.connection().connection
                        .firstOrError()
                        .ignoreElement()
                        .toSingleDefault(connection)
                } else {
                    LOG.e("establishing NEW connection to ${device.macAddress} ${device.name}, $luid, ${connectionCache.size} devices connected")
                    val newconnection = factory.transaction(device, luid)
                    server.get().getServerSync()!!.connection.setOnMtuChanged(device.bluetoothDevice) { m ->
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
                                        .map { mtu ->
                                            newconnection.connection().mtu.set(mtu)
                                            LOG.w("actual mtu $mtu")
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
                        .doFinally { connectionCache.remove(luid) }

                    newconnection.connection().subscribeConnection(rawConnection)
                    connectionCache[luid] = newconnection
                    //  updateActive(luid)
                    newconnection.connection().setOnDisconnect {
                        LOG.e("client onDisconnect $luid")
                        updateDisconnected(luid)
                        newconnection.bluetoothLeRadioModule().cancelTransaction()
                        radioModule.removeUke(luid)
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
            }.flatMap { c -> c }.subscribeOn(ioScheduler)

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