package net.ballmerlabs.uscatterbrain.util
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

val LOCKS = ConcurrentHashMap<String, CompletableMutex>()

class CompletableMutex(
    private val lock: LinkedBlockingDeque<CompletableSubject> = LinkedBlockingDeque(),
    val name: String,
    val scheduler: Scheduler
){

    fun await(): Single<CompletableMutex> {
        return Completable.defer {
            val subject = CompletableSubject.create()
            if (lock.size == 0) {
                subject.onComplete()
            }
            lock.push(subject)
            subject
        }.toSingleDefault(this)
    }

    fun release()  {
        lock.poll()?.onComplete()
    }
}

fun getMutex(name: String, scheduler: Scheduler): CompletableMutex {
    return LOCKS.compute(name) { k, v ->
        when(v) {
            null -> CompletableMutex(name = k, scheduler = scheduler)
            else -> v
        }
    }!!
}

fun releaseMutex(name: String) {
    val m = LOCKS.remove(name)
    m?.release()
}