package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.retryDelay
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val clientScheduler: Scheduler,
    val factory: ScatterbrainTransactionFactory,
    private val advertiser: Advertiser,
    private val server: Provider<ManagedGattServer>,
    private val scheduler: Provider<ScatterbrainScheduler>
) : LeState {
    private val transactionLock: AtomicReference<UUID?> = AtomicReference<UUID?>(null)
    private val transactionInProgress: AtomicInteger = AtomicInteger(0)
    private val votingUpdate = BehaviorSubject.create<AtomicReference<VotingStage>>()

    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()
    override val connectionCache: ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent> =
        ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent>()
    private val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()

    // a "channel" is a characteristic that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic> =
        ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>()
    private val LOG by scatterLog()

    override fun getVotingState(): Single<VotingStage> {
        return votingUpdate.takeUntil { v -> !v.get().stale.get() }.map { v -> v.get() }.firstOrError()
    }
    override fun transactionLockIsSelf(luid: UUID?): Boolean {
        val lock = transactionLock.get()
        return lock == luid || lock == null
    }


    override fun startTransaction(): Int {
        return transactionInProgress.incrementAndGet()
    }

    override fun stopTransaction(): Int {
        val t = transactionInProgress.updateAndGet { v -> when(v) {
            0 -> 0
            else -> v-1
        } }
        if (t == 0) {
           votingUpdate.map { v -> v.set(VotingStage()) }.ignoreElements().blockingAwait()
        }
        return t
    }

    override fun transactionLockAccquire(luid: UUID?): Boolean {
        val lock = transactionLock.getAndAccumulate(luid) { c, n ->
            when (c) {
                n -> n
                null -> n
                else -> c
            }
        } == null
        if (lock) {
          //  scheduler.get().pauseScan()
        }
        return lock
    }

    override fun transactionUnlock(luid: UUID): Boolean {
        LOG.e("removing ${transactionLock.get()} $luid")
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
        LOG.e("updateDisconnected $luid")
        val c = connectionCache.remove(luid)
        transactionLock.set(null)
        val device = c?.device()
        if (device != null) {
            server.get()?.getServerSync()?.disconnect(device)
            server.get()?.getServerSync()?.unlockLuid(luid)
        }
        c?.connection()?.dispose()
    }

    override fun updateGone(luid: UUID) {
        activeLuids.remove(luid)
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null
                && !activeLuids.containsKey(advertisingLuid)
    }

    override fun processScanResult(
        remoteUuid: UUID,
        device: RxBleDevice
    ): Maybe<HandshakeResult> {
        return Maybe.defer {
            establishConnectionCached(device, remoteUuid)
                .flatMapMaybe { cached ->
                    cached.connection().connection
                        .firstOrError()
                        .flatMapMaybe { raw ->
                            LOG.v("attempting to read hello characteristic")
                            raw.readCharacteristic(BluetoothLERadioModuleImpl.UUID_HELLO)
                                .flatMapMaybe { luid ->
                                    val luidUuid = BluetoothLERadioModuleImpl.bytes2uuid(luid)!!
                                    LOG.v("read remote luid from GATT $luidUuid")
                                    cached.bluetoothLeRadioModule().initiateOutgoingConnection(
                                        luidUuid
                                    ).onErrorComplete()
                                }
                                .onErrorComplete()
                        }
                }.subscribeOn(clientScheduler)
        }
    }


    override fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return when (scanResult.scanRecord.serviceData.keys.size) {
            1 -> scanResult.scanRecord.serviceData.keys.iterator().next()?.uuid
            0 -> null
            else -> throw IllegalStateException("too many luids")
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
                    Single.just(connection)
                } else {
                    LOG.e("establishing NEW connection to ${device.macAddress} ${device.name}, $luid, ${connectionCache.size} devices connected")
                    val rawConnection = retryDelay(device.establishConnection(false), 5, 1)
                        .subscribeOn(clientScheduler)
             //           .flatMapSingle { c -> c.requestMtu(128).ignoreElement().toSingleDefault(c) }
                        .doFinally { connectionCache.remove(luid) }
                        .doOnNext {
                            LOG.d("now connected ${device.macAddress}")
                        }
                    val newconnection = factory.transaction(device)
                    newconnection.connection().subscribeConnection(rawConnection)
                    connectionCache.putIfAbsent(luid, newconnection)
                  //  updateActive(luid)
                    newconnection.connection().setOnDisconnect {
                        LOG.e("client onDisconnect $luid")
                        updateDisconnected(luid)
                        if (connectionCache.isEmpty()) {
                           // LOG.e("connectionCache empty, removing luid")
                           // advertiser.removeLuid()
                            Completable.complete()
                        } else {
                            Completable.complete()
                        }.andThen(server.get().getServer().flatMapCompletable { s ->
                            s.unlockLuid(luid)
                            s.disconnect(device)
                            Completable.complete()
                        })

                    }
                    newconnection.connection().connection.map { newconnection }.firstOrError()
                }
            }

        return advertiser.setAdvertisingLuid()
            .andThen(connectSingle)
    }
    override fun refreshPeers(): Completable {
        return Completable.fromAction {
            LOG.v("refreshPeers called")
            activeLuids.clear()
        }
    }


    init {
        refreshInProgresss.accept(false)
        votingUpdate.onNext(AtomicReference(VotingStage()))
    }


}