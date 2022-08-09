package net.ballmerlabs.uscatterbrain.network

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class CircularBufferTest {
    @Test
    fun inputStreamCallbackWorks() {
        val callback = InputStreamObserver(256)
        val validate = Random.nextBytes(10)
        callback.onNext(validate)
        callback.onNext(validate)
        val test = ByteArray(10)
        val test2 = ByteArray(10)
        callback.read(test)
        callback.read(test2)
        assert(validate.contentEquals(test))
        assert(validate.contentEquals(test2))
    }


    @Test
    fun inputStreamCallbackBlocks() {
        val complete = AtomicBoolean(false)
        val written = AtomicBoolean(false)
        val thread = thread(start = true) {
            val callback = InputStreamObserver(256)
            val validate = Random.nextBytes(10)
            callback.onNext(validate)
            written.set(true)
            val bytes = ByteArray(15)
            callback.read(bytes)
            complete.set(true)
        }
        try {
            thread.join(100)
            assert(!complete.get())
            assert(written.get())
        } catch (timeout: TimeoutException) {
            assert(true)
        }
    }

    @Test
    fun readBeforeWriteWorks() {
        val written = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val callback = InputStreamObserver(256)
        val validate = Random.nextBytes(10)
        val thread = thread(start = true) {
            started.set(true)
            Thread.sleep(2000)
            callback.onNext(validate)
            written.set(true)
        }
        try {
            val bytes = ByteArray(10)
            assert(!written.get())
            callback.read(bytes)
            assert(started.get())
            thread.join(4000)
            assert(written.get())
        } catch (timeout: TimeoutException) {
            assert(true)
        }
    }
}