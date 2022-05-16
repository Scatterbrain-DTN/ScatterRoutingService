package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.schedulers.TestScheduler
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

    private val preferences: RouterPreferences = MockRouterPreferences()

    @Mock
    private lateinit var channel: WifiP2pManager.Channel

    private val broadcastReceiver = MockWifiDirectBroadcastReceiver(mock())


    @Test
    fun connectGroupTest() {

        val group = mock<WifiP2pGroup> {
            on { isGroupOwner } doReturn false
            on { passphrase } doReturn pass
            on { networkName } doReturn name
        }

        val wifiP2pManager = mock<WifiP2pManager> {
            on { connect(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                callback.onSuccess()
            }
            on { requestGroupInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                callback.onGroupInfoAvailable(group)
                broadcastReceiver.connectionInfoRelay.accept(mock {
                    on { isGroupOwner() } doReturn false
                    on { groupFormed() } doReturn true
                    on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
                })
            }
            on { requestConnectionInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ConnectionInfoListener
                callback.onConnectionInfoAvailable(mock{
                    on { groupOwnerAddress } doReturn InetAddress.getLocalHost()
                })
            }
        }

        val module = WifiDirectRadioModuleImpl(
                wifiP2pManager,
                context,
                datastore,
                preferences,
                scheduler,
                channel,
                broadcastReceiver
        )

        val info = module.connectToGroup(name, pass, 0)
                .timeout(10, TimeUnit.SECONDS)
                .blockingGet()

        assert(info.groupOwnerAddress() != null)
    }

}