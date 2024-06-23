package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import io.reactivex.Completable
import io.reactivex.Maybe
import net.ballmerlabs.scatterbrainsdk.HandshakeResult
import net.ballmerlabs.scatterproto.Optional
import net.ballmerlabs.scatterproto.Provides
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import proto.Scatterbrain
import java.util.UUID

interface BluetoothLEModule {
    /**
     * Run a transaction with an already connected peer. This requires
     * both a forward and a reverse connection setup.
     * To start a transaction first run initiateOutgoingConnection
     *
     * @param luid: the remote peer's luid
     * @param reverse set this to true if the connection is a reverse connection
     * @return the HandshakeResult from the transaction
     */
    fun handleConnection(
        luid: UUID,
        reverse: Boolean = false
    ): Maybe<HandshakeResult>

    /**
     * Returns true if there is an ongoing transaction
     */
    fun isBusy(): Boolean

    /**
     * Disposes of and shuts down the current transaction, even
     * if it hasn't completed yet
     */
    fun cancelTransaction()

    /**
     * Poke the remote peer's hello characteristic to start a transaction.
     * The remote peer should make a reverse connection to us before starting
     * the transaction
     * @param luid the remote peer's luid
     * @return completeable of success
     */
    fun initiateOutgoingConnection(
        luid: UUID
    ): Completable

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
    enum class Role {
        ROLE_SUPERUKE,
        ROLE_SUPERSEME,
        ROLE_UKE,
        ROLE_SEME;
        fun toSuper(): Role {
            return when(this) {
                ROLE_UKE -> ROLE_SUPERUKE
                ROLE_SEME -> ROLE_SUPERSEME
                else -> this
            }
        }

        fun toProto(): Scatterbrain.Role {
            return when(this) {
                ROLE_SUPERSEME -> Scatterbrain.Role.SUPER_SEME
                ROLE_SEME -> Scatterbrain.Role.SEME
                ROLE_SUPERUKE -> Scatterbrain.Role.SUPER_UKE
                ROLE_UKE -> Scatterbrain.Role.UKE
            }
        }
    }

    data class ConnectionRole(
        val role: Role,
        val drop: Boolean,
        val provides: Provides,
        val upgrade: Optional<net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket>
    )

    companion object {
        const val GATT_SIZE = 19
        const val TIMEOUT = 45
    }
}