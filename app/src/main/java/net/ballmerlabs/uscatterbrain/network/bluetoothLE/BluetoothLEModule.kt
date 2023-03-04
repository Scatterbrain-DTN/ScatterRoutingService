package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import com.polidea.rxandroidble2.RxBleDevice
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import java.util.*

interface BluetoothLEModule {
    /**
     * Stops active discovery
     */
    fun stopDiscover()

    /**
     * Returns a completable that completes when the current transaction is finished or emits an error
     * on a fatal failure to complete the transaction
     * @return Completable
     */
    fun awaitTransaction(): Completable

    /**
     * Returns an observable that emits a HandshakeResult for every completed transaction.
     * onError should only be called if this transport module is unable to complete future
     * transactions
     *
     * if this transport module is not actively discovering or advertising, the observable
     * will not emit any values and no attempt at enabling disovery or advertisement will be made
     * @return Observable emitting handshake results
     */
    fun observeCompletedTransactions(): Observable<HandshakeResult>

    /**
     * Returns an observable that emits a boolean, true if a transaction is in progress and false
     * if it has ended (completed or failed)
     *
     * @return Observable
     */
    fun observeTransactionStatus(): Observable<Boolean>

    /**
     * Clears the list of nearby peers, nearby devices currently in range will
     * be reconnected to if possible
     */
    fun clearPeers()

    /**
     * Removes the current wifi direct group if it exists
     * @param shouldRemove do nothing if false (what?)
     * @return completable
     */
    fun removeWifiDirectGroup(shouldRemove: Boolean): Completable



    /**
     * Handle an existing scan result
     * @param scanResult scan result
     * @return maybe for transaction
     */
    fun processScanResult(remoteUuid: UUID, bleDevice: RxBleDevice): Maybe<HandshakeResult>

    fun handleConnection(
        clientConnection: CachedLEConnection,
        device: RxBleDevice,
        luid: UUID
    ): Maybe<HandshakeResult>

    fun initiateOutgoingConnection(
        cachedConnection: CachedLEConnection,
        luid: UUID
    ): Maybe<HandshakeResult>

    /**
     * role is a generalized concept of "initiator" vs "acceptor"
     * with "SEME" being an initiator and "UKE" being acceptor
     * used for bootstrapping to another transport that may be asymmetric
     * and require some form of symmetry-breaking
     *
     * in the case of wifi direct this decides the group owner
     *
     * This is decided via the leader election process
     */
    enum class ConnectionRole {
        ROLE_UKE, ROLE_SEME
    }

    companion object {
        const val GATT_SIZE = 20
        const val TIMEOUT = 20
    }
}