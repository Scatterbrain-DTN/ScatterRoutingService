package net.ballmerlabs.uscatterbrain.util
import android.util.Log
import io.reactivex.Flowable
import io.reactivex.FlowableSubscriber
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.processors.PublishProcessor
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
class QueueItem<T>(
    val item: T?
)

class QueueSubject<T>(): FlowableSubscriber<T> {
    val queue = LinkedBlockingQueue<QueueItem<T>>()
    val complete = AtomicBoolean(false)
    val subscription = mutableSetOf<Subscription>()
    override fun onSubscribe(s: Subscription) {
        subscription.add(s)
        s.request(1)
    }

    override fun onNext(t: T?) {
        queue.put(QueueItem(t))
        subscription.forEach { s -> s.request(1) }
    }

    override fun onError(t: Throwable?) {
        complete.set(true)
    }

    fun hasItem(): Boolean {
        Log.v("debug","queue hasItem empty=${queue.isEmpty()} item=${queue.peek()?.item}")
        return !(queue.isEmpty() || queue.peek()?.item == null)
    }

    fun getOrNull(): Single<QueueItem<T>> {
        return Single.defer {
            if (complete.get() && (queue.isEmpty() || queue.peek()?.item == null)) {
                Single.just(QueueItem(null))
            } else {
                val item = queue.poll()?.item
                if (item != null)
                    Single.just(QueueItem(item))
                else
                    Single.just(QueueItem(null))
            }

        }
    }

    fun get(): Maybe<T> {
        return Maybe.defer {
            if (complete.get() && (queue.isEmpty() || queue.peek()?.item == null)) {
                Maybe.empty()
            } else {
                val item = queue.poll()?.item
                if (item != null)
                    Maybe.just(item)
                else
                    Maybe.empty()
            }

        }
    }

    override fun onComplete() {
        Log.e("debug", "queue complete?")
       complete.set(true)
        queue.put(QueueItem(null))
    }
}