package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import io.reactivex.Completable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.DeclareHashesPacket
import net.ballmerlabs.uscatterbrain.network.IdentityPacket
import net.ballmerlabs.uscatterbrain.network.RoutingMetadataPacket
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

const val pass = "fmefthisisahorriblepassphrase"
const val name = "DIRECT-fmoo"


@RunWith(RobolectricTestRunner::class)
class WifiDirectTest {

    init {
        logger = mockLoggerGenerator
    }

    @Before
    fun init() {
        packetOutputStream = ByteArrayOutputStream()
        preferences = MockRouterPreferences()
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

    private lateinit var preferences: MockRouterPreferences

    private lateinit var packetOutputStream: ByteArrayOutputStream

    private var broadcastReceiver = MockWifiDirectBroadcastReceiver(mock())

    @Mock
    private lateinit var wifiP2pManager: WifiP2pManager

    private lateinit var module: WifiDirectRadioModule

    private lateinit var compositeDisposable: CompositeDisposable

    private lateinit var bootstrapRequestComponentBuilder: BootstrapRequestSubcomponent.Builder

    private val delayScheduler = Schedulers.io()

    private fun buildModule(packets: InputStream = ByteArrayInputStream(byteArrayOf())) {
        broadcastReceiver = MockWifiDirectBroadcastReceiver(mock())
        val component = DaggerFakeRoutingServiceComponent.builder()
            .applicationContext(context)
            .wifiP2pManager(wifiP2pManager)
            .packetInputStream(packets)
            .rxBleClient(mock { })
            .mockPreferences(preferences)
            .packetOutputStream(packetOutputStream)
            .wifiDirectBroadcastReceiver(broadcastReceiver)
            .build()!!
        module = component.wifiDirectModule()
        bootstrapRequestComponentBuilder = component.bootstrapSubcomponent().get()

    }

    private fun handleRequestGroupInfo(
        callback: WifiP2pManager.GroupInfoListener,
        info: WifiDirectInfo,
        group: WifiP2pGroup? = null,
        groupInfoDelay: Long = 10,
        broadcastDelay: Long = 10,

        ) {
        val disp = Completable.fromAction {
            callback.onGroupInfoAvailable(group)
        }
            .subscribeOn(delayScheduler)
            .delay(groupInfoDelay, TimeUnit.MILLISECONDS)
            .andThen(Completable.fromAction {
                broadcastReceiver.connectionInfoRelay.accept(info)
            }
                .subscribeOn(delayScheduler)
                .delay(broadcastDelay, TimeUnit.MILLISECONDS))
            .subscribe()
        compositeDisposable.add(disp)
        Unit
    }


    private fun handleConnect(callback: WifiP2pManager.ActionListener, connectDelay: Long = 10) {
        val disp = Completable.fromAction {
            callback.onSuccess()
        }
            .subscribeOn(delayScheduler)
            .delay(connectDelay, TimeUnit.MILLISECONDS)
            .subscribe()

        compositeDisposable.add(disp)
    }

    private fun mockWifiP2p(
        connectDelay: Long = 10,
        groupInfoDelay: Long = 10,
        broadcastDelay: Long = 10,
        groupInfo: WifiP2pGroup? = null,
        wifiDirectInfo: WifiDirectInfo = DEFAULT_INFO
    ): WifiP2pManager {
        return mock {
            on { connect(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                handleConnect(callback, connectDelay)
            }
            on { requestGroupInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                handleRequestGroupInfo(
                    callback,
                    wifiDirectInfo,
                    groupInfo,
                    groupInfoDelay,
                    broadcastDelay
                )
            }

            on { createGroup(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ActionListener
                callback.onSuccess()
                broadcastReceiver.connectionInfoRelay.accept(mock {
                    on { groupFormed() } doReturn true
                    on { isGroupOwner() } doReturn true
                })
            }

            on { removeGroup(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ActionListener
                callback.onSuccess()
                broadcastReceiver.connectionInfoRelay.accept(mock {
                    on { groupFormed() } doReturn false
                })
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
        wifiP2pManager = mockWifiP2p()
        buildModule()
        val info = module.connectToGroup(name, pass, 10)
            .timeout(10, TimeUnit.SECONDS)
            .blockingGet()

        assert(info.groupOwnerAddress() != null)
    }


    private fun initEmptyHandshake(): InputStream {
        val os = ByteArrayOutputStream()

        val routingMetadataPacket = RoutingMetadataPacket.newBuilder().setEmpty().build()
        val identityPacket = IdentityPacket.newBuilder().setEnd().build()!!
        val declareHashesPacket = DeclareHashesPacket.newBuilder()
            .setHashesByte(listOf())
            .build()
        val blockdata = BlockHeaderPacket.newBuilder().setEndOfStream(true).build()
        routingMetadataPacket.writeToStream(os, delayScheduler).blockingAwait()
        identityPacket.writeToStream(os, delayScheduler).blockingAwait()
        declareHashesPacket.writeToStream(os, delayScheduler).blockingAwait()
        blockdata.writeToStream(os, delayScheduler).blockingAwait()
        return ByteArrayInputStream(os.toByteArray())
    }

    @Test
    fun bootstrapUke() {
        wifiP2pManager = mockWifiP2p()
        context = mock {
            on { getString(any()) } doReturn "blockdatacap"
        }
        preferences.putValue("blockdatacap", 1)
        buildModule(packets = initEmptyHandshake())
        val req = bootstrapRequestComponentBuilder
            .wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                    role = BluetoothLEModule.ConnectionRole.ROLE_UKE,
                    passphrase = pass,
                    name = name
                )
            )
            .build()!!
            .wifiBootstrapRequest()
        val res = module.bootstrapFromUpgrade(req).blockingGet()
        assert(res.success)
    }

    @Test
    fun bootstrapSeme() {
        wifiP2pManager = mockWifiP2p()
        context = mock {
            on { getString(any()) } doReturn "blockdatacap"
        }
        preferences.putValue("blockdatacap", 1)
        buildModule(packets = initEmptyHandshake())
        val req = bootstrapRequestComponentBuilder
            .wifiDirectArgs(
                BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs(
                    role = BluetoothLEModule.ConnectionRole.ROLE_SEME,
                    passphrase = pass,
                    name = name
                )
            )
            .build()!!
            .wifiBootstrapRequest()
        val res = module.bootstrapFromUpgrade(req).blockingGet()
        assert(res.success)
    }

    @Test
    fun connectGroupTestSweep() {
        for (connectDelay in LongProgression.fromClosedRange(0, 1000, 500)) {
            for (groupInfoDelay in LongProgression.fromClosedRange(0, 1000, 500)) {
                for (broadcastDelay in LongProgression.fromClosedRange(0, 1000, 500)) {
                    wifiP2pManager = mockWifiP2p(connectDelay, groupInfoDelay, broadcastDelay)
                    buildModule()
                    val info = module.connectToGroup(
                        name,
                        pass,
                        ((connectDelay + groupInfoDelay + broadcastDelay) / 1000).toInt() + 5
                    )
                        .timeout(10, TimeUnit.SECONDS)
                        .blockingGet()

                    assert(info.groupOwnerAddress() != null)
                }
            }
        }
    }

    @Test
    fun createGroup() {
        val groupInfo = mock<WifiP2pGroup> {
            on { passphrase } doReturn pass
            on { networkName } doReturn name
        }

        val p2pInfo = mock<WifiDirectInfo> {
            on { groupFormed() } doReturn true
            on { isGroupOwner() } doReturn true
        }
        wifiP2pManager = mock {
            on { connect(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                handleConnect(callback)
            }

            on { createGroup(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ActionListener
                callback.onSuccess()
            }
            on { requestGroupInfo(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                handleRequestGroupInfo(
                    callback,
                    p2pInfo,
                    group = null,
                )
            } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.GroupInfoListener
                handleRequestGroupInfo(callback, p2pInfo, groupInfo)
            }
        }
        buildModule()
        val bootstrap = module.createGroup()
            .timeout(10, TimeUnit.SECONDS)
            .blockingGet()
        assert(bootstrap.name == name)
        assert(bootstrap.passphrase == pass)
    }

    @Test
    fun createGroupAlreadyExists() {
        wifiP2pManager = mockWifiP2p(
            groupInfo = mock {
                on { passphrase } doReturn pass
                on { networkName } doReturn name
            }
        )
        buildModule()
        val bootstrap = module.createGroup()
            .timeout(10, TimeUnit.SECONDS)
            .blockingGet()
        assert(bootstrap.name == name)
        assert(bootstrap.passphrase == pass)
    }

    companion object {
        val DEFAULT_INFO = mock<WifiDirectInfo> {
            on { isGroupOwner() } doReturn false
            on { groupFormed() } doReturn true
            on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
        }
    }

}