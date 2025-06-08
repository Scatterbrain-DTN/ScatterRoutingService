package net.ballmerlabs.uscatterbrain.util

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

fun <T> Observable<T>.retryDelay(count: Int, seconds: Int): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i}
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
                .concatMapMaybe{ Maybe.timer(seconds.toLong(), TimeUnit.SECONDS) }
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
                .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i}
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
                .concatMapMaybe{ Maybe.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

data class MapLast<T>(
    val v: T?,
    val u: T?
)

fun <T> Observable<T>.concatMapLast(func: (T) -> T): Observable<T> {
    return this
        .map { v -> MapLast(v = v, u = null) }
        .concatWith(Observable.just(MapLast(v= null, u = null)))
        .scan { v, u ->
            if (u.v == null) {
                MapLast(v = func(v.v!!)!!, u = null)
            } else {
                MapLast(v = v.v!!, u = u.v)
            }
        }.flatMap { v ->
            if(v.v != null)
                Observable.just(v.v)
                    .concatWith(if (v.u != null) Observable.just(v.u) else Observable.empty())
            else
                Observable.empty()
        }

}

data class Enumerate<R>(
    val idx: Int? = null,
    val v: R? = null
)

fun <T, R> Observable<T>.enumerateMap(func: (T, Int) -> R): Observable<R> {
        return this
            .scan(Enumerate<R>()) { v, k ->
                val idx = v.idx?:0
                Enumerate(idx = idx+1, v = func(k, idx) )
        }.skip(1).map { v-> v.v!! }
}


fun <T> Flowable<T>.concatMapLast(func: (T) -> T): Flowable<T> {
    return this
        .map { v -> MapLast(v = v, u = null) }
        .concatWith(Flowable.just(MapLast(v= null, u = null)))
        .scan { v, u ->
            if (u.v == null) {
                MapLast(v = func(v.v!!)!!, u = null)
            } else {
                MapLast(v = v.v!!, u = u.v)
            }
        }.flatMap { v ->
            if(v.v != null)
                Flowable.just(v.v)
                    .concatWith(if (v.u != null) Flowable.just(v.u) else Flowable.empty())
            else
                Flowable.empty()
        }

}