package net.ballmerlabs.uscatterbrain.network.wifidirect

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleScanRecordMock
import com.polidea.rxandroidble2.scan.ScanRecord
import io.reactivex.Completable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.BootstrapRequestSubcomponent
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.network.*
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.IllegalStateException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

const val pass = "fmefthisisahorriblepassphrase"
const val name = "DIRECT-fmoo"


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class WifiDirectTest {

    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
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

    @Mock
    private lateinit var bluetoothManager: BluetoothManager

    @Mock
    private lateinit var wifiManager: WifiManager

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
            .bluetoothManager(bluetoothManager)
            .wifiManager(wifiManager)
            .build()!!
        val trans = component.getTransactionBuilder()
            .luid(UUID.randomUUID())
            .device(RxBleDeviceMock.Builder()
                .deviceMacAddress("ff:ff:ff:ff:ff:ff")
                .deviceName("")
                .bluetoothDevice(mock {
                    on { address } doReturn "ff:ff:ff:ff:ff:ff"
                })
                .connection(
                    RxBleConnectionMock.Builder()
                        .rssi(1)
                        .build()
                )
                .scanRecord(RxBleScanRecordMock.Builder().build())
                .build())
            .build()!!
        module = trans.wifiDirectRadioModule()
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
            broadcastReceiver.connectionInfoRelay.accept(mock {
                on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
                on { groupFormed() } doReturn true
                on { isGroupOwner() } doReturn false
            })
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

            on { requestConnectionInfo(any(), any()) }  doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ConnectionInfoListener
                val info = WifiP2pInfo()
                info.groupOwnerAddress = InetAddress.getLocalHost()
                info.isGroupOwner = false
                info.groupFormed = true
                callback.onConnectionInfoAvailable(info)
            }

            on { createGroup(any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[1] as WifiP2pManager.ActionListener
                callback.onSuccess()
                broadcastReceiver.connectionInfoRelay.accept(mock {
                    on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
                    on { groupFormed() } doReturn true
                    on { isGroupOwner() } doReturn true
                })
            }

            on { createGroup(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
                callback.onSuccess()
                broadcastReceiver.connectionInfoRelay.accept(mock {
                    on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
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
        val info = module.connectToGroup(name, pass, 10, FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .doOnSubscribe { shadowOf(Looper.getMainLooper()).idle() }
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
        val ackPacket = AckPacket.newBuilder(true).build()
        routingMetadataPacket.writeToStream(os, delayScheduler).timeout(1, TimeUnit.SECONDS).blockingAwait()
        identityPacket.writeToStream(os, delayScheduler).timeout(1, TimeUnit.SECONDS).blockingAwait()
        declareHashesPacket.writeToStream(os, delayScheduler).timeout(1, TimeUnit.SECONDS).blockingAwait()
        blockdata.writeToStream(os, delayScheduler).timeout(1, TimeUnit.SECONDS).blockingAwait()
        ackPacket.writeToStream(os, delayScheduler).timeout(1, TimeUnit.SECONDS).blockingAwait()
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
                    role = BluetoothLEModule.Role.ROLE_UKE,
                    passphrase = pass,
                    name = name,
                    band = FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ,
                    port = 9999
                )
            )
            .build()!!
            .wifiBootstrapRequest()
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
                    role = BluetoothLEModule.Role.ROLE_SEME,
                    passphrase = pass,
                    name = name,
                    band = FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ,
                    port = 9999
                )
            )
            .build()!!
            .wifiBootstrapRequest()
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
                        ((connectDelay + groupInfoDelay + broadcastDelay) / 1000).toInt() + 5,
                        band = FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                    )
                        .timeout(5, TimeUnit.SECONDS)
                        .blockingGet()

                    assert(info.groupOwnerAddress() != null)
                }
            }
        }
    }
/*
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
            on { createGroup(any(), any(), any()) } doAnswer { ans ->
                val callback = ans.arguments[2] as WifiP2pManager.ActionListener
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
        val bootstrap = module.createGroup(FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ, UUID.randomUUID(), UUID.randomUUID())
            .firstOrError()
            .timeout(5, TimeUnit.SECONDS)
            .blockingGet()
        assert(bootstrap.name == name)
        assert(bootstrap.passphrase == pass)
    }

 */

    companion object {
        val DEFAULT_INFO = mock<WifiDirectInfo> {
            on { isGroupOwner() } doReturn false
            on { groupFormed() } doReturn true
            on { groupOwnerAddress() } doReturn InetAddress.getLocalHost()
        }
    }

}