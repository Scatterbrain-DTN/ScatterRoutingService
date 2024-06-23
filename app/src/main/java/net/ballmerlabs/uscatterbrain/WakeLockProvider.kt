package net.ballmerlabs.uscatterbrain

interface WakeLockProvider {
    fun hold(): Int
    fun release(): Int
    fun releaseAll()
}