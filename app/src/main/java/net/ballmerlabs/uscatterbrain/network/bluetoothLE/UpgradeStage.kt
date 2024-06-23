package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import java.util.Random

/**
 * Manages state for the FSM to generate a BootstrapRequest or
 * UpgradePacket
 */
class UpgradeStage(private val provides: UpgradePacket) : LeDeviceSession.Stage {
    var sessionID = Random(System.nanoTime()).nextInt()

    override fun reset() {
        sessionID = Random(System.nanoTime()).nextInt()
    }

    fun getUpgrade(): UpgradePacket {
        return provides
    }
}