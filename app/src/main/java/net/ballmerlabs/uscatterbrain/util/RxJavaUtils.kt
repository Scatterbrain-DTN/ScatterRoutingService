package net.ballmerlabs.uscatterbrain.util

import io.reactivex.*
import java.util.concurrent.TimeUnit

fun <T> retryDelay(observable: Observable<T>, count: Int, seconds: Int): Observable<T> {
    return observable
            .retryWhen { errors: Observable<Throwable> ->
                errors
                        .zipWith(Observable.range(1, count)) { _: Throwable, i: Int -> i }
                        .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
            }
}

fun retryDelay(completable: Completable, count: Int, seconds: Int): Completable {
    return completable
            .retryWhen { errors: Flowable<Throwable> ->
                errors
                        .zipWith(Flowable.range(1, count)) { _: Throwable, i: Int -> i }
                        .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
            }
}

fun <T> retryDelay(single: Single<T>, count: Int, seconds: Int): Single<T> {
    return single
            .retryWhen { errors ->
                errors
                        .zipWith(Flowable.range(1, count)) { _, i: Int -> i }
                        .concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
            }
}

fun <T> retryDelay(maybe: Maybe<T>, count: Int, seconds: Int): Maybe<T> {
    return maybe
        .retryWhen { errors ->
            errors
                .zipWith(Flowable.range(1, count)) { _, i: Int -> i }
                .concatMapMaybe{ Maybe.timer(seconds.toLong(), TimeUnit.SECONDS) }
        }
}

fun <T> retryDelay(single: Single<T>, seconds: Int): Single<T> {
    return single
            .retryWhen { errors ->
                errors.concatMapSingle { Single.timer(seconds.toLong(), TimeUnit.SECONDS) }
            }
}