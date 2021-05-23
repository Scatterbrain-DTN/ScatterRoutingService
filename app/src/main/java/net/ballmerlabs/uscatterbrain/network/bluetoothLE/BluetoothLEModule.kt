package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Observable
import net.ballmerlabs.scatterbrainsdk.internal.HandshakeResult

interface BluetoothLEModule {
    fun startAdvertise()
    fun stopAdvertise()
    fun stopDiscover()
    fun startServer(): Boolean
    fun stopServer()
    fun awaitTransaction(): Completable
    fun observeTransactions(): Observable<HandshakeResult>
    fun discoverForever(): Observable<HandshakeResult>
    fun refreshPeers(): Completable

    /*
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