package net.ballmerlabs.uscatterbrain.scheduler

/**
 * dagger2 interface for ScatterbrainScheduler
 */
interface ScatterbrainScheduler {
    fun start()
    fun stop(): Boolean
    val isDiscovering: Boolean
    val isPassive: Boolean
}