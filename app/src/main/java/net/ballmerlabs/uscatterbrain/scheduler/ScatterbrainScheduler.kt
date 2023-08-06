package net.ballmerlabs.uscatterbrain.scheduler

/**
 * dagger2 interface for ScatterbrainScheduler
 */
interface ScatterbrainScheduler {
    fun start()
    fun stop(): Boolean
    fun pauseScan()
    fun unpauseScan()
    fun acquireWakelock()
    fun releaseWakeLock()
    val isDiscovering: Boolean
    val isPassive: Boolean
}