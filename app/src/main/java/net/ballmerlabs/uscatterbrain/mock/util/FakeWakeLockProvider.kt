package net.ballmerlabs.uscatterbrain.mock.util

import net.ballmerlabs.uscatterbrain.WakeLockProvider
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeWakeLockProvider @Inject constructor() : WakeLockProvider {
    private val counter = AtomicInteger()
    override fun releaseAll() {
        counter.set(0)
    }

    override fun hold(): Int {
        return counter.incrementAndGet()
    }

    override fun release(): Int {
        return counter.decrementAndGet()
    }
}