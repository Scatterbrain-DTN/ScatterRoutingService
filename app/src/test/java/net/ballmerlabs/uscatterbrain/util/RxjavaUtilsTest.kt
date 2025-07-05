package net.ballmerlabs.uscatterbrain.util

import android.os.Build
import io.reactivex.Flowable
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
        val items = (0..30).toList().map { v -> Pair(v, v) }

        val test = Observable.fromIterable(items)
            .enumerateMap { v, idx ->
                Pair(v.first, idx)
            }.toList()
            .blockingGet()

        println(items)
        println(test)
        assert(items == test)
    }

    @Test
    fun enumerateMapBoth() {
        val items = (0..30).toList().map { v -> Pair(v, v) }

        val testList = items.toMutableList()

        testList[items.size-1] = Pair(testList[items.size-1].first,99)


        val test = Observable.fromIterable(items)
            .enumerateMap { v, idx ->
                Pair(v.first, idx)
            }.concatMapLast { v -> Pair(v.first, 99) }
            .toList()
            .blockingGet()

        println(items)
        println(test)
        assert(testList == test)
    }


    @Test
    fun concatMapLast() {
        for (x in 1..11) {
            println("running $x")
            val testList = Observable.range(0, x).toList().blockingGet().toMutableList()
            testList[testList.size-1] = -1
            val list = Observable.range(0, x)
                .concatMapLast { v -> -1 }
                .toList()
                .blockingGet()

            println(list)
            println(testList)
            assert(list == testList)
        }
    }
}