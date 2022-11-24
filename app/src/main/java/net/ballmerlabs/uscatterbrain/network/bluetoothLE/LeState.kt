package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

@Singleton
data class LeState(
    val connectionCache: ConcurrentHashMap<UUID, CachedLEConnection> = ConcurrentHashMap<UUID, CachedLEConnection>()
)