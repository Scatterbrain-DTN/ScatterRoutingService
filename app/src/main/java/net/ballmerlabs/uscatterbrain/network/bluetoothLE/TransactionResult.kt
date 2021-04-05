package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothDevice
import java.util.*

/**
 * transactionresult is a combination optional and data class holding the result of a GATT
 * transaction. It is used to set the next stage in the FSM and optionally to bootstrap
 */
class TransactionResult<T> constructor(
        val nextStage: String,
        val device: BluetoothDevice,
        val luid: UUID?,
        val result: T? = null,
        val err: Boolean = false
) {
    fun hasResult(): Boolean {
        return result != null
    }

    companion object {
        const val STAGE_EXIT = "exit"
        const val STAGE_START = "start"
        const val STAGE_LUID_HASHED = "luid-hashed"
        const val STAGE_LUID = "luid"
        const val STAGE_ADVERTISE = "advertise"
        const val STAGE_ELECTION_HASHED = "election-hashed"
        const val STAGE_ELECTION = "election"
        const val STAGE_UPGRADE = "upgrade"
        const val STAGE_BLOCKDATA = "blockdata"
        const val STAGE_DECLARE_HASHES = "declarehashes"
        const val STAGE_IDENTITY = "identity"
    }

}