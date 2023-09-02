package net.ballmerlabs.uscatterbrain.network

import android.os.Build
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.TransactionResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class TransactionResultTest {

    @Test
    fun merge() {
        val result = TransactionResult.of(2, TransactionResult.STAGE_TERMINATE)
        val result2 = TransactionResult.empty<Int>()
        val n = result.merge(result2).blockingGet()
        assert(n.isPresent)
        assert(n.item == 2)
        assert(!n.isError)

        val juststage = TransactionResult.of<Int>(TransactionResult.STAGE_TERMINATE)
        val n2 = juststage.merge(result2).blockingGet()
        assert(n2.stage == TransactionResult.STAGE_TERMINATE)
    }

    @Test
    fun mergeError() {
        val exc = IllegalStateException("fmef")
        val result = TransactionResult.of(2, TransactionResult.STAGE_TERMINATE)
        val err = TransactionResult.err<Int>(exc)
        val n = result.merge(err).test()
        assert(n.errorCount() == 1)
    }

    @Test
    fun testConflict() {
        val value = TransactionResult.of(5)
        val empty = TransactionResult.empty<Int>()
        val n = value.merge(empty).test()
        assert(n.errorCount() == 0)

        val stage = TransactionResult.of<Int>(TransactionResult.STAGE_TERMINATE)
        val newstage = TransactionResult.of<Int>(TransactionResult.STAGE_ADVERTISE)
        val n2 = stage.merge(newstage).test()
        assert(n2.errorCount() == 1)
    }

}