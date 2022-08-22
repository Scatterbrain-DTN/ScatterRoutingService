package net.ballmerlabs.uscatterbrain

import net.ballmerlabs.uscatterbrain.db.sanitizeFilename
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
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
            assert(sanitizeFilename(filename) != filename)
            assert(false)
        } catch (exc: Exception) {
            assert(true)
        }

        try {
            assert(!sanitizeFilename(filename).contains(".."))
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
        assert(sanitizeFilename(i) == i)
        assert(sanitizeFilename(x) == x)
    }


}