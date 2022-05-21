package net.ballmerlabs.uscatterbrain

import com.google.protobuf.ByteString
import io.reactivex.disposables.CompositeDisposable
import net.ballmerlabs.uscatterbrain.db.entities.ApiIdentity
import net.ballmerlabs.uscatterbrain.db.sanitizeFilename
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
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
        assert(sanitizeFilename(filename) != filename)
        assert(!sanitizeFilename(filename).contains(".."))
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