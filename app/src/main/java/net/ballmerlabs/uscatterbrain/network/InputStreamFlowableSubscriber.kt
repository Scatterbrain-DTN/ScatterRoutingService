package net.ballmerlabs.uscatterbrain.network

import io.reactivex.FlowableSubscriber
import io.reactivex.disposables.Disposable
import org.reactivestreams.Subscription
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * serves as a bridge between a flowable and an inputstream with
 * a static sized buffer to old unread data
 */
class InputStreamFlowableSubscriber(capacity: Int) : InputStreamCallback(capacity), FlowableSubscriber<ByteArray?> {
    private var isDisposed = false
    private var subscription: Subscription? = null
    private var blocksize: Int = DEFAULT_BLOCKSIZE

    override fun onSubscribe(s: Subscription) {
        s.request(blocksize * 20.toLong())
        subscription = s
    }

    override fun onNext(t: ByteArray?) {
        if (!closed) {
            acceptBytes(t!!)
        }
    }

    override fun onError(e: Throwable) {
        throwable = e
        try {
            close()
        } catch (ignored: IOException) {
        }
    }

    override fun onComplete() {}

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        subscription?.request(max(blocksize, b.size).toLong())
        return super.read(b)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        subscription?.request(max(blocksize, min(b.size, len)).toLong())
        return super.read(b, off, len)
    }

    @Throws(IOException::class)
    override fun read(): Int {
        subscription?.request(1)
        return super.read()
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        subscription?.request(max(blocksize.toLong(), n))
        return super.skip(n)
    }

    @Throws(IOException::class)
    override fun close() {
        super.close()
    }

    companion object {
        const val DEFAULT_BLOCKSIZE = 20
    }
}