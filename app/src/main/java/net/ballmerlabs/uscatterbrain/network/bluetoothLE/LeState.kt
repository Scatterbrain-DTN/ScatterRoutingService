package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

interface LeState {
    val connectionCache: ConcurrentHashMap<UUID, ScatterbrainTransactionSubcomponent>
    // a "channel" is a characteristc that protobuf messages are written to.
    val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>
    val createGroupCache: AtomicReference<BehaviorSubject<WifiDirectBootstrapRequest>>

    fun disconnectServer(device: RxBleDevice?)
    fun startServer(): Completable
    fun stopServer()
    fun getServerSync(): GattServerConnectionSubcomponent?
    fun getServer(): Maybe<GattServerConnectionSubcomponent>

    fun shouldConnect(res: ScanResult): Boolean

    fun shouldConnect(luid: UUID): Boolean

    fun activeCount(): Int

    fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID
    ): Single<ScatterbrainTransactionSubcomponent>

    fun getAdvertisedLuid(scanResult: ScanResult): UUID?

    fun votingLock(): Completable

    fun votingUnlock()

    fun updateGone(luid: UUID)
    fun startTransaction(): Int

    fun dumpPeers(): Completable

    fun stopTransaction(): Int
    fun updateActive(uuid: UUID?): Boolean

    fun updateActive(scanResult: ScanResult): Boolean

    fun transactionLockIsSelf(luid: UUID?): Boolean

    fun transactionLockAccquire(luid: UUID?): Boolean
    fun transactionUnlock(luid: UUID): Boolean
    /**
     * Handle an existing scan result
     * @param scanResult scan result
     * @return maybe for transaction
     */
    fun processScanResult(remoteUuid: UUID, device: RxBleDevice): Maybe<HandshakeResult>
    fun updateDisconnected(luid: UUID)

    /**
     * attempt to reinitiate a connection with all nearby peers and
     * run another transaction. This should be called sparingly if new data is available
     * If a refresh is already in progress this function calls oncomplete when the current
     * refresh is complete
     * @returns Observable emitting handshake results
     */
    fun refreshPeers(): Completable

    fun setupChannels() {
        for (i in 0 until BluetoothLERadioModuleImpl.NUM_CHANNELS) {
            val channel = BluetoothLERadioModuleImpl.incrementUUID(
                BluetoothLERadioModuleImpl.SERVICE_UUID_LEGACY,
                i + 1
            )
            channels[channel] =
                BluetoothLERadioModuleImpl.LockedCharacteristic(makeCharacteristic(channel), i)
        }
    }
}