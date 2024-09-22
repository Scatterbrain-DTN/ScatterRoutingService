package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.jakewharton.rxrelay2.PublishRelay
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.subjects.PublishSubject
import net.ballmerlabs.scatterproto.ScatterSerializable
import net.ballmerlabs.scatterproto.incrementUUID
import net.ballmerlabs.scatterproto.uuid2bytes
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.FakeGattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.FakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.FakeTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.ScatterbrainTransactionSubcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.Companion.SERVICE_UUID_LEGACY
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.proto.AckPacket
import net.ballmerlabs.uscatterbrain.network.proto.BlockHeaderPacket
import net.ballmerlabs.uscatterbrain.network.proto.BlockSequencePacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.MockWifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.util.getBogusRxBleDevice
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class GattClientTests {
    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
        logger = mockLoggerGenerator
    }


    private val scheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-single"))
    private val ioScheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-io"))

    private val disposable = CompositeDisposable()

    private lateinit var gattServer: GattServer

    private lateinit var connectionBuilder: FakeGattServerConnectionSubcomponent.Builder

    val preferences = MockRouterPreferences()

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var manager: BluetoothManager

    @Mock
    private lateinit var wifiManager: WifiManager

    @Mock
    private lateinit var advertiser: Advertiser

    @Mock
    private lateinit var androidGattServer: BluetoothGattServer

    @Mock
    private lateinit var rxBleClient: RxBleClient

    lateinit var component: ScatterbrainTransactionSubcomponent

    lateinit var parent: FakeRoutingServiceComponent

    lateinit var connection: CachedLeConnection

    lateinit var leState: MockLeState

    lateinit var mockConnection: RxBleConnectionMock

    private val channel = incrementUUID(SERVICE_UUID_LEGACY, 1)

    private val notif = PublishRelay.create<ByteArray>()

    val myLuid = UUID.randomUUID()

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        val device = getBogusRxBleDevice("ff:ff:ff:ff:ff:ff")
        parent = DaggerFakeRoutingServiceComponent.builder()
            .applicationContext(context)
            .wifiP2pManager(mock { })
            .rxBleClient(rxBleClient)
            .packetOutputStream(ByteArrayOutputStream())
            .packetInputStream(ByteArrayInputStream(byteArrayOf()))
            .wifiDirectBroadcastReceiver(MockWifiDirectBroadcastReceiver(mock { }))
            .mockPreferences(preferences)
            .bluetoothManager(manager)
            .wifiManager(wifiManager)
            .build()!!

        val gattServerComponent = parent.gattConnectionBuilder().gattServer(androidGattServer).timeoutConfiguration(
            TimeoutConfiguration(5, TimeUnit.SECONDS, ioScheduler)
        ).build()
        leState = MockLeState(gattServerComponent)

        connection = CachedLEConnectionImpl(
            ioScheduler = ioScheduler,
            parseScheduler = scheduler,
            device = device,
            advertiser = advertiser,
            leState = leState,
            luid = myLuid
        )

        component = gattServerComponent
            .transaction()
            .connection(connection)
            .device(device)
            .luid(myLuid)
            .build()!!




        mockConnection = RxBleConnectionMock.Builder()
            .rssi(1)
            .addService(gattService.uuid, gattService.characteristics)
            .characteristicReadCallback(BluetoothLERadioModuleImpl.UUID_SEMAPHOR) { device, char, result ->
                result.success(uuid2bytes(channel))
            }
            .characteristicWriteCallback(channel) { device, char, data, result ->
                result.success()
            }
            .descriptorWriteCallback(channel, GattServerConnection.CLIENT_CONFIG) { d, c, data, result ->
                result.success()
            }
            .notificationSource(channel, notif)
            .build()

        disposable.add(connection.subscribeNotifs().subscribe())

    }


    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
        disposable.dispose()
    }



    fun <T: ScatterSerializable<U>, U: MessageLite> testPacket(packet: T, func: () -> Single<T>): T {
        connection.subscribeConnection(Observable.never<RxBleConnection?>().mergeWith(Observable.just(mockConnection as RxBleConnection)))
        val disp =  packet.writeToStream(20, scheduler)
            .blockingGet()
            .subscribe(notif)
        disposable.add(disp)

        return func().blockingGet()
    }


    @Test
    fun testAck() {
        val packet = AckPacket.newBuilder(true).build()
        val ack = testPacket(packet) { connection.readAck() }
        assert(ack == packet)
    }

    @Test
    fun testBlockData() {
        val packet = BlockHeaderPacket.newBuilder()
            .setSessionID(5)
            .setFilename("test")
            .setDate(Date())
            .setMime("test")
            .setApplication("test")
            .setHashes(listOf(ByteString.copyFrom(byteArrayOf(1, 2 ,3)), ByteString.copyFrom(byteArrayOf(3, 2, 1))))
            .build()
        val header = testPacket(packet) { connection.readBlockHeader() }
        assert(header == packet)
    }

    @Test
    fun testBlockSequence() {
        val packet = BlockSequencePacket.newBuilder()
            .setSequenceNumber(42)
            .setData(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4, 5)))
            .build()


        val n = testPacket(packet) { connection.readBlockSequence() }

        assert(n == packet)


    }


}