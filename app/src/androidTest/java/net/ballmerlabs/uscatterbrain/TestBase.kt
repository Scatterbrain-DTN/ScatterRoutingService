package net.ballmerlabs.uscatterbrain

import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.ServiceTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.scatterbrainsdk.ScatterbrainAPI
import net.ballmerlabs.scatterbrainsdk.internal.BinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.BinderWrapperImpl
import net.ballmerlabs.scatterbrainsdk.internal.MockBinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.ScatterbrainBroadcastReceiverImpl
import org.junit.Before
import org.junit.Rule

abstract class TestBase {
    protected val testCoroutineScope = CoroutineScope(Dispatchers.Default)
    protected lateinit var binder: BinderWrapper
    protected lateinit var regularBinder: ScatterbrainAPI

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
        val broadcastReceiver= ScatterbrainBroadcastReceiverImpl()
        broadcastReceiver.coroutineScope = testCoroutineScope
        broadcastReceiver.context = ApplicationProvider.getApplicationContext()
        binder = BinderWrapperImpl(
                ApplicationProvider.getApplicationContext(),
                broadcastReceiver,
                binderProvider
        )
        runBlocking { binder.startService() }
        regularBinder = ScatterbrainAPI.Stub.asInterface(b)
        regularBinder.clearDatastore()
        broadcastReceiver.register()
    }
}