package net.ballmerlabs.uscatterbrain

import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.rule.ServiceTestRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class ApiTest {
    private lateinit var routingService : ScatterRoutingService

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    @Throws(TimeoutException::class)
    fun bindTest() {
        val bindIntet = Intent(
                ApplicationProvider.getApplicationContext(),
                ScatterRoutingService::class.java
        )

        val binder: IBinder = serviceRule.bindService(bindIntet)
    }
}