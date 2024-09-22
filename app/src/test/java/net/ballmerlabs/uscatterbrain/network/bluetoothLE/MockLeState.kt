package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.os.ParcelUuid
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.FakeGattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.util.toUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MockLeState(
    private val serverConnection: FakeGattServerConnectionSubcomponent,
    val connectionFactory: PublishSubject<CachedLeConnection> = PublishSubject.create(),
    private val resultFactory: Observable<HandshakeResult> = Observable.just(HandshakeResult(1, 1, HandshakeResult.TransactionStatus.STATUS_SUCCESS)),
    val connectionCache: ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent> = ConcurrentHashMap(),
    override val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic> = ConcurrentHashMap(),
) : LeState {

    private val activeLuids: ConcurrentHashMap<UUID, Boolean> = ConcurrentHashMap<UUID, Boolean>()
    private var server: GattServerConnectionSubcomponent? = null
    private val transactionInProgress = AtomicInteger(0)
    private val transactionLock: AtomicReference<UUID?> = AtomicReference<UUID?>(null)

    init {
        setupChannels()
    }

    override fun startServer(): Completable {
        return Completable.fromAction {
            server = serverConnection
        }
    }

    override fun observeDisconnects(): Observable<RxBleDevice> {
        return Observable.never()
    }

    override fun stopServer():  Completable {
        server = null
        return Completable.complete()
    }

    override fun getServerSync(): GattServerConnectionSubcomponent? {
        return server
    }

    override fun getServer(): Single<GattServerConnectionSubcomponent> {
        return Single.fromCallable {
            server!!
        }
    }

    override fun shouldConnect(res: ScanResult): Boolean {
        val advertisingLuid = getAdvertisedLuid(res)
        return advertisingLuid != null
                && !activeLuids.containsKey(advertisingLuid)
    }

    override fun shouldConnect(luid: UUID): Boolean {
        return !activeLuids.containsKey(luid)
    }

    override fun activeCount(): Int {
        return activeLuids.size
    }

    override fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID,
        reverse: Boolean
    ): Single<ScatterbrainTransactionSubcomponent> {
        return connectionFactory.firstOrError()
            .map { c -> serverConnection.transaction().connection(c).luid(luid).build() }
    }

    override fun getAdvertisedLuid(scanResult: ScanResult): UUID? {
        return scanResult.scanRecord.serviceData[ParcelUuid(Advertiser.LUID_DATA)]?.toUuid()
    }

    override fun connection(): List<ScatterbrainTransactionSubcomponent> {
        return connectionCache.values.toList()
    }

    override fun updateGone(luid: UUID, err: Throwable) {
        activeLuids.remove(luid)
    }

    override fun startTransaction(): Int {
        return transactionInProgress.incrementAndGet()
    }

    override fun dumpPeers(forget: Boolean): Completable {
        return Completable.fromAction {
            connectionCache.clear()
        }
    }

    override fun stopTransaction(): Int {
        return transactionInProgress.updateAndGet { v ->
            when (v) {
                0 -> 0
                else -> v - 1
            }
        }
    }

    override fun updateActive(uuid: UUID?): Boolean {
        return if (uuid != null) activeLuids.put(uuid, true) == null else false
    }

    override fun updateActive(scanResult: ScanResult): Boolean {
        return updateActive(getAdvertisedLuid(scanResult))
    }

    override fun processScanResult(remoteUuid: UUID, device: RxBleDevice): Completable {
        return resultFactory.firstElement().ignoreElement()
    }

    override fun updateDisconnected(luid: UUID, reason: String) {
        val c = connectionCache.remove(luid)
        c?.connection()?.disconnect()
        val device = c?.device()
        if (device != null) {
            //  server.get()?.disconnect(device)
            getServerSync()?.cachedConnection()?.unlockLuid(luid)
            if (connectionCache.size == 0) {
                getServerSync()?.connection()?.resetMtu(device.macAddress)
            }
        }
        transactionLock.set(null)
    }

    override fun refreshPeers(): Completable {
        return Completable.complete()
    }

}