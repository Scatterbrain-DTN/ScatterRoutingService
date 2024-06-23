package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanResult
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import net.ballmerlabs.scatterproto.incrementUUID
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Global helper class for bluetooth LE. Holds connection state and handles connecting, disconnecting,
 * and starting and stopping the gatt server
 */
interface LeState {
    /** a "channel" is a characteristc that protobuf messages are written to. */
    val channels: ConcurrentHashMap<UUID, BluetoothLERadioModuleImpl.LockedCharacteristic>


    /**
     * Disconnects the specified peer. The connection will be asynchronously cleaned up
     * in the background some time after this function is called.
     *
     * @param luid luid of remote peer to disconnect
     * @param reason used for logging internally, can be any string
     */
    fun updateDisconnected(luid: UUID, reason: String)


    /**
     * Starts the gatt server and event handlers.
     *
     * @return completable completing when server is started
     */
    fun startServer(): Completable

    /**
     * Shuts down the gatt server, calling gattServer.close() and disposing of the event loop
     *
     * @return completable completing when server is stopped
     *
     */
    fun stopServer(): Completable

    /**
     * Returns the gatt server if it is started, null if it is not started
     *
     * @return the gatt server componet
     */
    fun getServerSync(): GattServerConnectionSubcomponent?

    /**
     * Returns a single that emits the gatt server when it is started, waiting
     * indefinitely until it is started by startServer()
     */
    fun getServer(): Single<GattServerConnectionSubcomponent>

    /**
     * Returns true if internal logic suggests we should connect to this peer
     * currently this means the peer is not in the active transaction set
     *
     * @param res ScanResult containing luid of remote peer. If luid is not present this function returns false
     *
     * @return true if we should connect
     */
    fun shouldConnect(res: ScanResult): Boolean

    /**
     * Returns true if internal logic suggests we should connect to this peer
     * currently this means the peer is not in the active transaction set
     *
     * @param luid the remote peer's luid
     *
     * @return true if we should connect
     */
    fun shouldConnect(luid: UUID): Boolean

    /**
     * Gets the number of peers in the active transaction set
     */
    fun activeCount(): Int

    /**
     * Establishes a physical BLE connection with the remote peer, adding
     * it to the connection cache and allocation transaction state if successful.
     * if the device is already connected, a cached connection / transaction is returned
     * no transaction is run automatically after connection.
     *
     * @param device ble device to connect to
     * @param luid the remote device's luid
     * @param reverse whether this is a reverse connection
     *
     * @return the transaction object for this connection
     */
    fun establishConnectionCached(
        device: RxBleDevice,
        luid: UUID,
        reverse: Boolean = false
    ): Single<ScatterbrainTransactionSubcomponent>

    fun getAdvertisedLuid(scanResult: ScanResult): UUID?

    /**
     * Gets a snapshot of the connection cache
     *
     * @return list of transaction handles
     */
    fun connection(): List<ScatterbrainTransactionSubcomponent>

    /**
     * Return an observable emitting peers that have been observed as disconnecting
     * from EITHER the gatt client or server
     *
     * @return observable
     */
    fun observeDisconnects(): Observable<RxBleDevice>

    /**
     * Remove a peer from the active peers set.
     * This means a connection will be attempted as soon as the peer is available
     *
     * @param luid peer luid to update
     * @param err a throwable used as the disconnection reason.
     * The peer will only be removed if the err value is NOT a regular disconnection exception
     */
    fun updateGone(luid: UUID, err: Throwable)

    /**
     * Increments the transaction counter and returns the current value.
     * This is only used for metrics
     */
    fun startTransaction(): Int

    /**
     * Remove all currently connected peers in the connection cache
     * and stops their transactions.
     * @param forget pass true to also remove them from the active transaction set to force a reconnect
     */
    fun dumpPeers(forget: Boolean): Completable

    /**
     * Decrements the transaction counter, returning the current value
     */
    fun stopTransaction(): Int

    /**
     * Adds a peer to the active transaction set. This means no new connections
     * will be attempted to this peer until new data becomes available or its
     * luid is randomized.
     *
     * @param uuid luid of the peer to add
     * @return true if the peer was not active before
     */
    fun updateActive(uuid: UUID?): Boolean

    /**
     * Adds a peer to the active transaction set. This means no new connections
     * will be attempted to this peer until new data becomes available or its
     * luid is randomized.
     *
     * if the provided ScanResult doesn't contain an luid no action will be taken
     *
     * @param scanResult ScanResult from ble scan containing luid.
     * @return true if the peer was not active before
     */
    fun updateActive(scanResult: ScanResult): Boolean

    /**
     * Handle an existing scan result
     * @param  remoteUuid remote peer's luid
     * @return completable for end of transaction
     */
    fun processScanResult(remoteUuid: UUID, device: RxBleDevice): Completable

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
            val channel = incrementUUID(
                BluetoothLERadioModuleImpl.SERVICE_UUID_LEGACY,
                i + 1
            )
            channels[channel] =
                BluetoothLERadioModuleImpl.LockedCharacteristic(gattService.getCharacteristic(channel), i)
        }
    }
}