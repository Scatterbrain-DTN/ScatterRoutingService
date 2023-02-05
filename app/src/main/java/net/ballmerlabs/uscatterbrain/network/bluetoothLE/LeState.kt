package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface LeState {
    val connectionCache: ConcurrentHashMap<UUID, CachedLEConnection>
    val activeLuids: ConcurrentHashMap<UUID, Boolean>
    val transactionLock: AtomicReference<UUID?>
    // a "channel" is a characteristc that protobuf messages are written to.
    val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharactersitic>

    fun updateConnected(luid: UUID): Boolean
    fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID
    ): Single<CachedLEConnection>

    fun getAdvertisedLuid(scanResult: ScanResult): UUID?

    /**
     * Return true if the scanresult contains a connectable device
     * @param result ScanResult
     * @return true if we should connect
     */
    fun shouldConnect(res: ScanResult): Boolean

    fun updateDisconnected(luid: UUID)

    /**
     * attempt to reinitiate a connection with all nearby peers and
     * run another transaction. This should be called sparingly if new data is available
     * If a refresh is already in progress this function calls oncomplete when the current
     * refresh is complete
     * @returns Observable emitting handshake results
     */
    fun refreshPeers(): Observable<HandshakeResult>
}