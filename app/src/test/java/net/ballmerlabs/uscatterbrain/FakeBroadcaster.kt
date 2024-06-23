package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddrs
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopPower
import net.ballmerlabs.uscatterbrain.network.desktop.IdentityImportState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeBroadcaster @Inject constructor()  : Broadcaster{
    override fun broadcastState(
        state: IdentityImportState?,
        power: DesktopPower?,
        addrs: DesktopAddrs?,
        clientApps: Boolean
    ) {

    }
}