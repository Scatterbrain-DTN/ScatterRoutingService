package net.ballmerlabs.uscatterbrain.util

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import java.util.concurrent.TimeUnit

fun <T> Observable<T>.retryDelay(count: Int, seconds: Int): Observable<T> {
    return this
        .retryWhen { errors: Observable<Throwable> ->
            errors
                .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i}
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