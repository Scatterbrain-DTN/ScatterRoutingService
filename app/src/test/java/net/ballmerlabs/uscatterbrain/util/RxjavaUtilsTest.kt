package net.ballmerlabs.uscatterbrain.util

import android.os.Build
import io.reactivex.Observable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class RxjavaUtilsTest {

    @Test
    fun enumerateMap() {
        val items = (0..30).toList()

        val test = Observable.fromIterable(items)
            .enumerateMap { v, idx ->
                idx
            }.toList()
            .blockingGet()

        println(items)
        println(test)
        assert(items == test)
    }


    @Test
    fun concatMapLast() {
        for (x in 1..11) {
            println("running $x")
            val list = Observable.range(0, x)
                .concatMapLast { v -> -1 }
                .toList()
                .blockingGet()

            println(list)
            assert(list.last() == -1)
        }
    }
}