package net.ballmerlabs.uscatterbrain

import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.rule.ServiceTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainAPI
import net.ballmerlabs.scatterbrainsdk.internal.MockBinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.BinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.BinderWrapperImpl
import net.ballmerlabs.scatterbrainsdk.internal.ScatterbrainBroadcastReceiverImpl
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class ApiTest {
    private val testCoroutineScope = CoroutineScope(Dispatchers.Default)
    private lateinit var binder: BinderWrapper

    @get:Rule
    val serviceRule = ServiceTestRule()

    @ExperimentalCoroutinesApi
    @Before
    fun init() {
        val bindIntet = Intent(
                ApplicationProvider.getApplicationContext(),
                ScatterRoutingService::class.java
        )

        val b: IBinder = serviceRule.bindService(bindIntet)
        val binderProvider: BinderProvider = MockBinderProvider(b)
        val broadcastReceiver= ScatterbrainBroadcastReceiverImpl(
                ApplicationProvider.getApplicationContext(),
                testCoroutineScope
        )
        binder = BinderWrapperImpl(
                ApplicationProvider.getApplicationContext(),
                broadcastReceiver,
                binderProvider
        )
        runBlocking { binder.startService() }
        val api = ScatterbrainAPI.Stub.asInterface(b)
        api.clearDatastore()
        broadcastReceiver.register()
    }

    @Test
    @Throws(TimeoutException::class)
    fun startTest() {
        runBlocking { binder.startService() }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessage() {
        val message = ScatterMessage.newBuilder()
                .setApplication("testing")
                .setBody(byteArrayOf(0))
                .build()
        runBlocking { binder.sendMessage(message) }
    }


}