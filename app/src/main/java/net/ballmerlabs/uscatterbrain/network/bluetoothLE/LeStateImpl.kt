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
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val server: Provider<ManagedGattServer>,
): LeState {
    override val transactionLock: AtomicBoolean = AtomicBoolean(false)
    //avoid triggering concurrent peer refreshes
    private val refreshInProgresss = BehaviorRelay.create<Boolean>()
    override val activeLuids: ConcurrentHashMap<UUID, RxBleDevice> = ConcurrentHashMap<UUID, RxBleDevice>()
    // a "channel" is a characteristc that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>
    = ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>()
    private val LOG by scatterLog()

    override fun updateConnected(luid: UUID, device: RxBleDevice): Boolean {
        return if (activeLuids.put(luid, device) == null) {
            return true
        } else {
            false
        }
    }

    @Synchronized
    override fun updateDisconnected(luid: UUID) {
        LOG.e("updateDisconnected $luid")
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null && !activeLuids.containsKey(advertisingLuid)
    }

    override fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return when (scanResult.scanRecord.serviceData.keys.size) {
            1 -> scanResult.scanRecord.serviceData.keys.iterator().next()?.uuid
            0 -> null
            else -> throw IllegalStateException("too many luids")
        }
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
                            Observable.fromIterable(activeLuids.entries)
                                .concatMapMaybe { v ->
                                    LOG.v("refreshing peer ${v.key}")
                                    v.value.establishConnection(false)
                                        .flatMapMaybe { conn ->
                                            val c = CachedLEConnection(channels, operationsScheduler, v.value, conn)
                                            module.initiateOutgoingConnection(c, v.key)
                                                .onErrorComplete()
                                        }
                                        .firstOrError()
                                        .toMaybe()
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