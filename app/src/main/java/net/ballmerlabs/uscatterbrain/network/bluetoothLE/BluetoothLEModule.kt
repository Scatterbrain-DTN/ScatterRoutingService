package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Observable
import net.ballmerlabs.scatterbrainsdk.HandshakeResult

interface BluetoothLEModule {
    /**
     * Stats LE advertise on scatterbrain UUID
     * This should run offloaded on the adapter until stopAdvertise is called
     */
    fun startAdvertise()

    /**
     * Stops LE advertise
     */
    fun stopAdvertise()

    /**
     * Stops active discovery
     */
    fun stopDiscover()

    /**
     * Starts GATT server accept loop.
     *
     */
    fun startServer()

    /**
     * Stops the gatt server accept loop
     */
    fun stopServer()

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
    fun observeTransactions(): Observable<HandshakeResult>

    /**
     * Similar to observeTransactions(), but enables active discovery before emitting any
     * HandshakeResults
     *
     * @return Observable emitting handshake results
     */
    fun discoverForever(): Observable<HandshakeResult>

    /**
     * attempt to reinitiate a connection with all nearby peers and
     * run another transaction. This should be called sparingly if new data is available
     * If a refresh is already in progress this function calls oncomplete when the current
     * refresh is complete
     * @return completable
     */
    fun refreshPeers(): Completable

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
        const val TIMEOUT = 10
    }
}