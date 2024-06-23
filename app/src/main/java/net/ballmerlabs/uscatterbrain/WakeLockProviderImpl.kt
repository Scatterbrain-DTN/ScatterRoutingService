package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeLockProviderImpl @Inject constructor(
    private val powerManager: PowerManager,
    private val context: Context
) : WakeLockProvider {
    private val counter = AtomicInteger()
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        context.getString(
            R.string.wakelock_tag
        )
    )
    override fun hold(): Int {
        if(!wakeLock.isHeld)
            wakeLock.acquire(10*60*1000L)
        return counter.incrementAndGet()
    }

    override fun release(): Int {
        val count =  counter.decrementAndGet()
        if(wakeLock.isHeld && count == 0)
            wakeLock.release()
        return count
    }

    override fun releaseAll() {
        if(wakeLock.isHeld)
            wakeLock.release()
        counter.set(0)
    }
}