package net.ballmerlabs.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import com.google.android.gms.common.util.Hex
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
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
    fun emptyRead() {
        val lock = ReentrantLock()
        lock.lock()
        val stream = object : InputStream() {
            override fun read(): Int {
                lock.lock()
                return 0
            }
        }

        val packet = ScatterSerializable.parseWrapperFromCRC(AckPacket.parser(), stream, Schedulers.io())
        val testpacket = packet.timeout(2, TimeUnit.SECONDS)
            .test()
            .awaitDone(3, TimeUnit.SECONDS)
            .assertTerminated()
            .assertError(TimeoutException::class.java)
    }

    @Test
    fun reproduceWeirdIssueThing() {
        val iter = 20
        val a = mutableListOf<ByteArray>()
        for (x in 5..iter) {
            a.add(Random.nextBytes(x))
        }

        val buf = InputStreamFlowableSubscriber(iter*4096)
        for (x in a) {
            val obs = Bytes.from(ByteArrayInputStream(x), 2)
                    .subscribeOn(Schedulers.single())
            obs.subscribe(buf)
        }

        for (x in a) {
            print("${x.size} ")
            println()
            val bytes = ByteArray(x.size)
            val compare = ByteArray(x.size)
            buf.read(bytes)
            println(Hex.bytesToStringLowercase(x))
            println(Hex.bytesToStringLowercase(bytes))
            assert(bytes.contentEquals(x))
            assert(!bytes.contentEquals(compare))
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