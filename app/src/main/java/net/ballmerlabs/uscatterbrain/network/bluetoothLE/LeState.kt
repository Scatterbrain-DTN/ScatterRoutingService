package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Single
import java.util.*
import java.util.concurrent.ConcurrentHashMap

interface LeState {
    val connectionCache: ConcurrentHashMap<UUID, CachedLEConnection>
    val activeLuids: ConcurrentHashMap<UUID, Boolean>
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
}