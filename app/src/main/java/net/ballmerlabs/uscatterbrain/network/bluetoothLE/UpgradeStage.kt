package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import java.util.*

/**
 * Manages state for the FSM to generate a BootstrapRequest or
 * UpgradePacket
 */
class UpgradeStage(private val provides: AdvertisePacket.Provides) : LeDeviceSession.Stage {
    var sessionID = Random(System.nanoTime()).nextInt()

    override fun reset() {
        sessionID = Random(System.nanoTime()).nextInt()
    }
}