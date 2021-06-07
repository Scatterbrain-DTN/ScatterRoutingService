package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Base64
import android.util.Log
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.util.*
import kotlin.collections.HashMap

/**
 * Manages state for the FSM to generate a BootstrapRequest or
 * UpgradePacket
 */
class UpgradeStage(private val provides: AdvertisePacket.Provides) : LeDeviceSession.Stage {
    var sessionID = Random(System.nanoTime()).nextInt()

    override fun reset() {
        sessionID = Random(System.nanoTime()).nextInt()
    }

    companion object {
        const val TAG = "UpgradeStage"
    }
}