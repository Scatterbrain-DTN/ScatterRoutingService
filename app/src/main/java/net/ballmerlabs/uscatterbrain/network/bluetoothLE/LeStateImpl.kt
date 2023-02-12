package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.jakewharton.rxrelay2.BehaviorRelay
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionFactory
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler,
    val factory: ScatterbrainTransactionFactory,
    private val advertiser: Advertiser,
    private val server: Provider<ManagedGattServer>
): LeState {
    private val transactionLock:AtomicReference<UUID?> = AtomicReference<UUID?>(null)
    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()
    override val connectionCache: ConcurrentHashMap<UUID, CachedLEConnection> = ConcurrentHashMap<UUID, CachedLEConnection>()
    override val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()
    // a "channel" is a characteristc that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>
    = ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>()
    private val LOG by scatterLog()


    override fun transactionLockIsSelf(luid: UUID?): Boolean {
        val lock = transactionLock.get()
        return lock == luid || lock == null
    }

    override fun transactionLockAccquire(luid: UUID?): Boolean {
        updateActive(luid)
        return transactionLock.getAndAccumulate(luid) { c, n ->
            when(c) {
                n -> n
                null -> n
                else -> c
            }
        } == null
    }

    override fun transactionUnlock(luid: UUID): Boolean {
        LOG.e("removing ${transactionLock.get()} $luid")
        return transactionLock.accumulateAndGet(luid) { old, new ->
            if(old?.equals(new) == true) {
                null
            } else {
                old
            }
        } == null
    }

   override fun updateActive(uuid: UUID?): Boolean {
        return if(uuid != null) activeLuids.put(uuid, true) == null else false
    }

    override fun updateActive(scanResult: ScanResult): Boolean {
        return updateActive(getAdvertisedLuid(scanResult))
    }

    @Synchronized
    override fun updateDisconnected(luid: UUID) {
        LOG.e("updateDisconnected $luid")
        activeLuids.remove(luid)
        val c = connectionCache.remove(luid)
        val device = c?.device
        transactionLock.set(null)
        server.get().disconnect(device)
        c?.dispose()
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null
                && !activeLuids.containsKey(advertisingLuid)
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
    ): Single<CachedLEConnection> {
        val connectSingle =
            Single.fromCallable {
                LOG.e(
                    "establishing cached connection to ${device.macAddress}, $luid, ${connectionCache.size} devices connected"
                )
                val newconnection = CachedLEConnection(channels, operationsScheduler, device)
                val connection = connectionCache[luid]
                if (connection != null) {
                    connection
                } else {
                    val rawConnection = device.establishConnection(false)
                        .doOnError { connectionCache.remove(luid) }
                        .doOnNext {
                            LOG.d("established cached connection to ${device.macAddress}")
                        }

                    newconnection.setOnDisconnect {
                        LOG.e("client onDisconnect $luid")
                        updateDisconnected(luid)
                        if (connectionCache.isEmpty()) {
                            advertiser.removeLuid()
                        } else {
                            Completable.complete()
                        }
                    }
                    newconnection.subscribeConnection(rawConnection)
                    connectionCache.putIfAbsent(luid, newconnection)
                    newconnection
                }
            }

        return advertiser.setAdvertisingLuid(getHashUuid(advertiser.myLuid.get()) ?: UUID.randomUUID())
            .andThen(connectSingle)
    }

    override fun refreshPeers(): Observable<HandshakeResult> {
        LOG.v("refreshPeers called")
        return refreshInProgresss
            .firstOrError()
            .flatMapObservable { b ->
                if (!b) {
                    refreshInProgresss.takeUntil { v -> !v }
                        .flatMap {
                            val module = factory.transaction().bluetoothLeRadioModule()
                            Observable.fromIterable(connectionCache.entries)
                                .concatMapMaybe { v ->
                                    LOG.v("refreshing peer ${v.key}")
                                    module.initiateOutgoingConnection(v.value, v.key)
                                        .onErrorComplete()
                                }
                        }
                } else {
                    LOG.v("refresh already in progress, skipping")
                    Observable.empty()
                }
            }
    }


    init {
        refreshInProgresss.accept(false)
    }



}