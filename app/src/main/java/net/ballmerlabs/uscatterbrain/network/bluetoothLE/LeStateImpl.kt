package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.getHashUuid
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class LeStateImpl @Inject constructor(
    @Named(RoutingServiceComponent.NamedSchedulers.IO) private val operationsScheduler: Scheduler,
    private val advertiser: Advertiser
): LeState {

    override val connectionCache: ConcurrentHashMap<UUID, CachedLEConnection> = ConcurrentHashMap<UUID, CachedLEConnection>()
    override val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()
    // a "channel" is a characteristc that protobuf messages are written to.
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>
    = ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>()
    private val LOG by scatterLog()

    override fun updateConnected(luid: UUID): Boolean {
        return if (activeLuids.putIfAbsent(luid, true) == null) {
            return true
        } else {
            false
        }
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
                    LOG.e("cache HIT")
                    connection
                } else {
                    val rawConnection = device.establishConnection(false)
                        .doOnError { connectionCache.remove(luid) }
                        .doOnNext {
                            LOG.e("established cached connection to ${device.macAddress}")
                        }
                    newconnection.setOnDisconnect {
                        LOG.e("client onDisconnect $luid")
                        val conn = connectionCache.remove(luid)
                        conn?.dispose()
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


}