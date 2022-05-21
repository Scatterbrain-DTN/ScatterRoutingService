package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.Completable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private val scheduler = TestScheduler()
const val pass = "fmefthisisahorriblepassphrase"
const val name = "DIRECT-fmoo"


@RunWith(RobolectricTestRunner::class)
class WifiDirectTest {

    init {
        logger = mockLoggerGenerator
    }

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        compositeDisposable = CompositeDisposable()
    }

    @After
    fun cleanup() {
        compositeDisposable.dispose()
        Mockito.validateMockitoUsage()
    }

    @Mock
    private lateinit var context: Context

    private var broadcastReceiver = MockWifiDirectBroadcastReceiver(mock())

    @Mock
    private lateinit var wifiP2pManager: WifiP2pManager

    private lateinit var module: WifiDirectRadioModule

    private lateinit var compositeDisposable: CompositeDisposable

    private val delayScheduler = Schedulers.io()

    private fun buildModule() {
        broadcastReceiver = MockWifiDirectBroadcastReceiver(mock())
        module = DaggerFakeRoutingServiceComponent.builder()
                .applicationContext(context)
                .wifiP2pManager(wifiP2pManager)
                .wifiDirectBroadcastReceiver(broadcastReceiver)
                .build()!!.wifiDirectModule()!!
    }

    private fun mockWifiP2pConnectGroupTest(
            connectDelay: Long = 1000,
            groupInfoDelay: Long = 1000,
            broadcastDelay: Long = 1000,
            groupInfo: WifiP2pGroup? = null
    ): WifiP2pManager {
        return mock {
            on { connect(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                val disp = Completable.fromAction {
                    callback.onSuccess()
                }
                        .subscribeOn(delayScheduler)
                        .delay(connectDelay, TimeUnit.MILLISECONDS)
                        .subscribe()

                compositeDisposable.add(disp)
                Unit
            }
            on { requestGroupInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                val disp = Completable.fromAction {
                    callback.onGroupInfoAvailable(groupInfo)
                }
                        .subscribeOn(delayScheduler)
                        .delay(groupInfoDelay, TimeUnit.MILLISECONDS)
                        .andThen(Completable.fromAction {
                            broadcastReceiver.connectionInfoRelay.accept(mock {
                                on { isGroupOwner() } doReturn false
                                on { groupFormed() } doReturn true
                                on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
                            })
                        }
                                .subscribeOn(delayScheduler)
                                .delay(broadcastDelay, TimeUnit.MILLISECONDS))
                        .subscribe()
                compositeDisposable.add(disp)
                Unit
            }
        }
    }

    private fun mockWifiP2pCreateGroup(
            connectDelay: Long = 1000,
            groupInfoDelay: Long = 1000,
            broadcastDelay: Long = 1000
    ): WifiP2pManager {
        return mock {
            on { connect(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                val disp = Completable.fromAction {
                    callback.onSuccess()
                }
                        .subscribeOn(delayScheduler)
                        .delay(connectDelay, TimeUnit.MILLISECONDS)
                        .subscribe()

                compositeDisposable.add(disp)
                Unit
            }
            on { requestGroupInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                val disp = Completable.fromAction {
                    callback.onGroupInfoAvailable(mock {
                        on { passphrase } doReturn pass
                        on { networkName } doReturn name
                    })
                }
                        .subscribeOn(delayScheduler)
                        .delay(groupInfoDelay, TimeUnit.MILLISECONDS)
                        .andThen(Completable.fromAction {
                            broadcastReceiver.connectionInfoRelay.accept(mock {
                                on { isGroupOwner() } doReturn false
                                on { groupFormed() } doReturn true
                                on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
                            })
                        }
                                .subscribeOn(delayScheduler)
                                .delay(broadcastDelay, TimeUnit.MILLISECONDS))
                        .subscribe()
                compositeDisposable.add(disp)
                Unit
            }
        }
    }

    @Test
    fun buildBlankModule() {
        buildModule()
        module.hashCode()
    }

    @Test
    fun connectGroupTest() {
        wifiP2pManager = mockWifiP2pConnectGroupTest()
        buildModule()
        val info = module.connectToGroup(name, pass, 10)
                .timeout(10, TimeUnit.SECONDS)
                .blockingGet()

        assert(info.groupOwnerAddress() != null)
    }

    @Test
    fun connectGroupTestSweep() {
        for(connectDelay in LongProgression.fromClosedRange(0,1000, 500)) {
            for(groupInfoDelay in LongProgression.fromClosedRange(0, 1000, 500)) {
                for(broadcastDelay in LongProgression.fromClosedRange(0, 1000, 500)) {
                    wifiP2pManager = mockWifiP2pConnectGroupTest(connectDelay, groupInfoDelay, broadcastDelay)
                    buildModule()
                    val info = module.connectToGroup(name, pass, ((connectDelay + groupInfoDelay + broadcastDelay)/1000).toInt()+5)
                            .timeout(10, TimeUnit.SECONDS)
                            .blockingGet()

                    assert(info.groupOwnerAddress() != null)
                }
            }
        }
    }

    @Test
    fun createGroup() {
        wifiP2pManager = mockWifiP2pCreateGroup()
        buildModule()
        val bootstrap = module.createGroup()
                .timeout(10, TimeUnit.SECONDS)
                .blockingGet()
        assert(bootstrap.name == name)
        assert(bootstrap.passphrase == pass)
    }

}