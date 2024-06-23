package net.ballmerlabs.uscatterbrain

import android.os.Build
import net.ballmerlabs.scatterproto.sanitizeFilename
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ApiTest {

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
    }

    @Test
    @Throws(TimeoutException::class)
    fun preventSimpleDirectoryTraversalAttack() {
        val filename = "../fmef"
        try {
            assert(net.ballmerlabs.scatterproto.sanitizeFilename(filename) != filename)
            assert(false)
        } catch (exc: Exception) {
            assert(true)
        }

        try {
            assert(!net.ballmerlabs.scatterproto.sanitizeFilename(filename).contains(".."))
            assert(false)
        } catch (exc: Exception) {
            assert(true)
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun allowsNormalFilename() {
        val i = "fmef"
        val x = "fmef_text"
        assert(net.ballmerlabs.scatterproto.sanitizeFilename(i) == i)
        assert(net.ballmerlabs.scatterproto.sanitizeFilename(x) == x)
    }


}