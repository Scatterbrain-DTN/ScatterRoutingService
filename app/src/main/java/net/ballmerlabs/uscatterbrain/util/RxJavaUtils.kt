package net.ballmerlabs.uscatterbrain.util

import com.github.davidmoten.rx2.flowable.Transformers
import io.reactivex.BackpressureStrategy
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun <T> Observable<T>.retryDelay(count: Int, seconds: Int): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i }
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Observable<T>.retryDelay(seconds: Int, timeUnit: TimeUnit): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .concatMapSingle { Single.timer(seconds.toLong(), timeUnit) }
        }
}

fun <T> Flowable<T>.retryDelay(seconds: Int): Flowable<T> {
    return this
        .retryWhen { errors: Flowable<Throwable> ->
            errors
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun Completable.retryDelay(seconds: Int): Completable {
    return this
        .retryWhen { errors: Flowable<Throwable> ->
            errors
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Single<T>.retryDelay(seconds: Int): Single<T> {
    return this
        .retryWhen { errors ->
            errors
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Maybe<T>.retryDelay(seconds: Int): Maybe<T> {
    return this
        .retryWhen { errors ->
            errors
                .concatMapMaybe { Maybe.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}


fun <T> Observable<T>.retryDelay(seconds: Int): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Observable<T>.retryDelay(count: Int, seconds: Int, timeUnit: TimeUnit): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i }
                .concatMapSingle { Single.timer(seconds.toLong(), timeUnit) }
        }
}

fun <T> Flowable<T>.retryDelay(count: Int, seconds: Int): Flowable<T> {
    return this
        .retryWhen { errors: Flowable<Throwable> ->
            errors
                .zipWith(Flowable.range(1, count)) { _: Throwable, i: Int -> i }
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun Completable.retryDelay(count: Int, seconds: Int): Completable {
    return this
        .retryWhen { errors: Flowable<Throwable> ->
            errors
                .zipWith(Flowable.range(1, count)) { _: Throwable, i: Int -> i }
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Single<T>.retryDelay(count: Int, seconds: Int): Single<T> {
    return this
        .retryWhen { errors ->
            errors
                .zipWith(Flowable.range(1, count)) { _, i: Int -> i }
                .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> Maybe<T>.retryDelay(count: Int, seconds: Int): Maybe<T> {
    return this
        .retryWhen { errors ->
            errors
                .zipWith(Flowable.range(1, count)) { _, i: Int -> i }
                .concatMapMaybe { Maybe.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

data class MapLast<T>(
    val v: T?,
    val u: T?,
)

data class MapLastFix<T>(
    val v: T?,
)

fun <T> Observable<T>.concatMapLast(func: (T) -> T): Observable<T> {
    return this.toFlowable(BackpressureStrategy.BUFFER)
        .compose(Transformers.mapLast(func))
        .toObservable()
}

data class Enumerate<R>(
    val idx: Int? = null,
    val v: R? = null,
)

fun <T, R> Observable<T>.enumerateMap(func: (T, Int) -> R): Observable<R> {
    return this
        .scan(Enumerate<R>()) { v, k ->
            val idx = v.idx ?: 0
            Enumerate(idx = idx + 1, v = func(k, idx))
        }.skip(1).map { v -> v.v!! }
}


fun <T, R> Flowable<T>.enumerateMap(func: (T, Int) -> R): Flowable<R> {
    return this
        .scan(Enumerate<R>()) { v, k ->
            val idx = v.idx ?: 0
            Enumerate(idx = idx + 1, v = func(k, idx))
        }.skip(1).map { v -> v.v!! }
}


fun <T> Flowable<T>.concatMapLast(func: (T) -> T): Flowable<T> {
    return this.compose(Transformers.mapLast(func))

}