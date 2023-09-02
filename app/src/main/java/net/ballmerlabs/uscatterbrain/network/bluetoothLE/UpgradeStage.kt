package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import java.util.Random

/**
 * Manages state for the FSM to generate a BootstrapRequest or
 * UpgradePacket
 */
class UpgradeStage(private val provides: VotingResult) : LeDeviceSession.Stage {
    var sessionID = Random(System.nanoTime()).nextInt()

    override fun reset() {
        sessionID = Random(System.nanoTime()).nextInt()
    }
}