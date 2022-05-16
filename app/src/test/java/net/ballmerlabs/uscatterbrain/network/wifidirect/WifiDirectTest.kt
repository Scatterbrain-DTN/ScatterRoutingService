package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.schedulers.TestScheduler
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.RouterPreferences
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.network.wifidirect.MockWifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private val scheduler = TestScheduler()
const val pass = "fmefthisisahorriblepassphrase"
const val name = "DIRECT-fmoo"


@RunWith(MockitoJUnitRunner::class)
class WifiDirectTest {

    @Before
    fun before() {
        logger = mockLoggerGenerator
    }

    @Mock
    private lateinit var wifiP2pManager: WifiP2pManager

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var datastore: ScatterbrainDatastore

    @Mock
    private lateinit var channel: WifiP2pManager.Channel

    @Test
    fun connectGroupTest() {
        val module = DaggerFakeRoutingServiceComponent.builder()
                .applicationContext(context)
                ?.build()!!
                .wifiDirectModule()!!

        val info = module.connectToGroup(name, pass, 10)
                .timeout(10, TimeUnit.SECONDS)
                .blockingGet()

        assert(info.groupOwnerAddress() != null)
    }

}