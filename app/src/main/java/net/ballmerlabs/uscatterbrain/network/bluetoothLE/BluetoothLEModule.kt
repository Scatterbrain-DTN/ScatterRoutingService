package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Maybe
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import java.util.UUID

interface BluetoothLEModule {
    /**
     * Stops active discovery
     */
    fun stopDiscover()


    fun handleConnection(
        luid: UUID
    ): Maybe<HandshakeResult>

    fun initiateOutgoingConnection(
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
        const val GATT_SIZE = 19
        const val TIMEOUT = 150
    }
}