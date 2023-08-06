package net.ballmerlabs.uscatterbrain.util
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.subjects.CompletableSubject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

val LOCKS = ConcurrentHashMap<String, CompletableMutex>()

class CompletableMutex(
    private val lock: AtomicReference<CompletableSubject?> = AtomicReference(null),
    val name: String,
){

    fun await(): Single<CompletableMutex> {
        return Completable.defer {
            lock.getAndUpdate { v ->
                when(v) {
                    null -> CompletableSubject.create()
                    else -> v
                }
            }?:Completable.complete()
        }.toSingleDefault(this)
    }

    fun release()  {
        LOCKS.remove(name)
        lock.getAndSet(null)?.onComplete()
    }
}

fun getMutex(name: String): CompletableMutex {
    return LOCKS.compute(name) { k, v ->
        when(v) {
            null -> CompletableMutex(name = k)
            else -> v
        }
    }!!
}

fun releaseMutex(name: String) {
    val m = LOCKS.remove(name)
    m?.release()
}