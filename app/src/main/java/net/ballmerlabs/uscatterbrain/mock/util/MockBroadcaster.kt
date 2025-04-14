package net.ballmerlabs.uscatterbrain.mock.util

import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddrs
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopPower
import net.ballmerlabs.uscatterbrain.network.desktop.IdentityImportState

class MockBroadcaster : Broadcaster {

    override fun broadcastState(
        state: IdentityImportState?,
        power: DesktopPower?,
        addr: DesktopAddrs?,
        clientApps: Boolean
    ) {

    }
}