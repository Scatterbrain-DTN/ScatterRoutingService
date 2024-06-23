package net.ballmerlabs.uscatterbrain.network.desktop

interface Broadcaster {
    fun broadcastState(
        state: IdentityImportState?= null,
        power: DesktopPower? = null,
        addr: DesktopAddrs? = null,
        clientApps: Boolean = false
    )
}