package com.example.uscatterbrain

import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import net.ballmerlabs.uscatterbrain.DaggerRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class WifiDirect {
    private lateinit var wifiDirectRadioModule: WifiDirectRadioModule
    private val ctx = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun initRadioModule() {
        wifiDirectRadioModule = DaggerRoutingServiceComponent.builder()
                .applicationContext(ctx)
                ?.build()!!.scatterRoutingService()!!.wifiDirect
        wifiDirectRadioModule.registerReceiver()
    }

    @After
    fun deinitRadioModule() {
        wifiDirectRadioModule.unregisterReceiver()
    }


    @Test
    fun createGroup() {
        wifiDirectRadioModule.createGroup("DIRECT-fmeftastic", "fmeftasticmlady")
                .timeout(30, TimeUnit.SECONDS)
                .blockingAwait()
    }
}