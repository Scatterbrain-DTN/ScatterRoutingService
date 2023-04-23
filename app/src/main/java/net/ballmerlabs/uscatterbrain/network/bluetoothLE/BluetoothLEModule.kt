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
        const val TIMEOUT = 200
    }
}