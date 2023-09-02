package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.bluetooth.*
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Pair
import com.google.protobuf.MessageLite
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import com.polidea.rxandroidble2.mockrxandroidble.RxBleClientMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleScanRecordMock
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.FakeGattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.AckPacket
import net.ballmerlabs.uscatterbrain.network.ScatterSerializable
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule.Companion.GATT_SIZE
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection.Companion.CLIENT_CONFIG
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.MockWifiDirectBroadcastReceiver
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
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class GattServerTest {
    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
        logger = mockLoggerGenerator
    }


    private val scheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-single"))
    private val ioScheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-io"))

    private lateinit var disposable: CompositeDisposable

    private lateinit var gattServer: GattServer

    private lateinit var connectionBuilder: FakeGattServerConnectionSubcomponent.Builder

    private lateinit var preferences: MockRouterPreferences

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var manager: BluetoothManager

    @Mock
    private lateinit var wifiManager: WifiManager


    @Mock
    private lateinit var androidGattServer: BluetoothGattServer

    @Mock
    private lateinit var rxBleClient: RxBleClient

    init {
        logger = mockLoggerGenerator
    }

    @Before
    fun init() {
        disposable = CompositeDisposable()
        preferences = MockRouterPreferences()
        MockitoAnnotations.openMocks(this)
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
        disposable.dispose()
    }


    private fun setupModule() {
        val component = DaggerFakeRoutingServiceComponent.builder()
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
        gattServer = component.gattServer()
        connectionBuilder = component.gattConnectionBuilder()
    }

    private fun getConnection(): GattServerConnection {
        return connectionBuilder
            .gattServer(androidGattServer)
            .timeoutConfiguration(
                TimeoutConfiguration(
                    10,
                    TimeUnit.SECONDS,
                    scheduler
                )
            )
            .build()
            .connection()
    }

    @Test
    fun gattServerInitialized() {
        setupModule()
        gattServer.hashCode()
    }

    @Test
    fun serverConfig() {
        setupModule()
        val connection = getConnection()
        val config = ServerConfig()
            .addService(mock {
                on { uuid } doReturn UUID.randomUUID()
            })

        connection.initializeServer(config).blockingAwait()
    }

    private fun getBogusRxBleDevice(mac: String): RxBleDevice {
        return RxBleDeviceMock.Builder()
            .deviceMacAddress(mac)
            .deviceName("")
            .bluetoothDevice(mock {
                on { address } doReturn mac
            })
            .connection(
                RxBleConnectionMock.Builder()
                    .rssi(1)
                    .build()
            )
            .scanRecord(RxBleScanRecordMock.Builder().build())
            .build()
    }

    private fun getCharacteristic(
        char: UUID,
        descriptorid: UUID? = null,
        notify: Boolean = false
    ): BluetoothGattCharacteristic {

        val nested = mock<BluetoothGattCharacteristic> {
            on { uuid } doReturn char
        }

        val descriptor = mock<BluetoothGattDescriptor> {
            on { uuid } doReturn descriptorid
            on { characteristic } doReturn nested
        }

        val clientConfig = mock<BluetoothGattDescriptor> {
            on { uuid } doReturn CLIENT_CONFIG
            on { characteristic } doReturn nested
        }

        return mock {
            on { uuid } doReturn char
            if (descriptorid != null) {
                on { getDescriptor(descriptorid) } doReturn descriptor
            }
            if (notify) {
                on { getDescriptor(CLIENT_CONFIG) } doReturn clientConfig
            }
        }
    }

    private fun <T> mockGattConnection(
        config: ServerConfig,
        get: (conn: GattServerConnection) -> Observable<T>,
        trigger: (conn: GattServerConnection, device: RxBleDevice) -> Unit
    ): Observable<T> {
        val mac = "ff:ff:ff:ff:ff:ff"
        val device = getBogusRxBleDevice(mac)

        rxBleClient = RxBleClientMock.Builder()
            .addDevice(device)
            .build()

        setupModule()

        val connection = getConnection()
        connection.initializeServer(config).blockingAwait()

        val replay = ReplaySubject.create<T>()
        get(connection)
            .timeout(10, TimeUnit.SECONDS, scheduler)
            .subscribe(replay)

        return replay
            .doOnSubscribe { trigger(connection, device) }
    }

    private fun getServerConfig(
        characteristic: BluetoothGattCharacteristic
    ): ServerConfig {
        return ServerConfig()
            .addService(mock {
                on { uuid } doReturn BluetoothLERadioModuleImpl.SERVICE_UUID
                on { characteristics } doReturn arrayListOf(characteristic)
            })

    }

    @Test
    fun characteristicWrite() {
        val value = byteArrayOf(1, 2, 3, 4)

        val char = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9786")
        val characteristic = getCharacteristic(char)
        val config = getServerConfig(characteristic)

        val res = mockGattConnection(
            config,
            get = { connection ->
                connection.getEvents().filter { c -> c.uuid == char }
            },
            trigger = { connection, device ->
                connection.gattServerCallback.onCharacteristicWriteRequest(
                    device.bluetoothDevice,
                    0,
                    characteristic,
                    false,
                    false,
                    0,
                    value
                )
            }
        ).blockingFirst()
        assert(res.value!!.contentEquals(value))
    }

    @Test
    fun characteristicRead() {
        val char = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9786")
        val characteristic = getCharacteristic(char)
        val config = getServerConfig(characteristic)
        val id = 255
        val res = mockGattConnection(
            config,
            get = { connection ->
                connection.getEvents().filter { c -> c.uuid == char }
            },
            trigger = { connection, device ->
                connection.gattServerCallback.onCharacteristicReadRequest(
                    device.bluetoothDevice,
                    id,
                    0,
                    characteristic
                )
            }
        ).blockingFirst()
        assert(res.value == null)
        assert(res.requestID == id)
    }

    @Test
    fun descriptorWrite() {
        val value = byteArrayOf(1, 2, 3, 4)

        val id = 255
        val char = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9786")
        val des = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9787")
        val characteristic = getCharacteristic(char, descriptorid = des)
        val config = getServerConfig(characteristic)

        val res = mockGattConnection(
            config,
            get = { connection ->
                connection.getEvents().filter { c -> c.uuid == des && c.characteristic.uuid == char }
            },
            trigger = { connection, device ->
                connection.gattServerCallback.onDescriptorWriteRequest(
                    device.bluetoothDevice,
                    id,
                    characteristic.getDescriptor(des),
                    false,
                    false,
                    0,
                    value
                )
            }
        ).blockingFirst()
        assert(res.value!!.contentEquals(value))
        assert(res.requestID == id)
    }


    @Test
    fun descriptorRead() {
        val char = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9786")
        val des = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9787")
        val characteristic = getCharacteristic(char, descriptorid = des)
        val config = getServerConfig(characteristic)
        val id = 255
        val res = mockGattConnection(
            config,
            get = { connection ->
                connection.getEvents().filter { c -> c.uuid == des && c.characteristic.uuid == char }
            },
            trigger = { connection, device ->
                connection.gattServerCallback.onDescriptorReadRequest(
                    device.bluetoothDevice,
                    id,
                    0,
                    characteristic.getDescriptor(des)
                )
            }
        ).blockingFirst()
        assert(res.value == null)
        assert(res.requestID == id)
    }
    private inline fun <reified T: MessageLite, reified U: ScatterSerializable<T>> testNotify(
        isIndication: Boolean,
        packets: ScatterSerializable<T>,
        parser: ScatterSerializable.Companion.Parser<T,U>,
        timeout: Long = 10,
        ): Single<ScatterSerializable<T>> {
        return Single.defer {
            println("testNotify $isIndication $timeout")
            val char = UUID.fromString("5C7E5EB0-540B-4675-9137-DC5235AA9786")
            val characteristic = getCharacteristic(char, notify = true)
            val config = getServerConfig(characteristic)
            val mac = "ff:ff:ff:ff:ff:ff"
            val device = getBogusRxBleDevice(mac)

            rxBleClient = RxBleClientMock.Builder()
                .addDevice(device)
                .build()

            manager = mock {
                on {
                    getConnectionState(device.bluetoothDevice, BluetoothProfile.GATT_SERVER)
                } doReturn BluetoothProfile.STATE_CONNECTED
            }
            var connection: GattServerConnection? = null
            setupModule()

            androidGattServer = mock {
                on {
                    notifyCharacteristicChanged(
                        any(),
                        any(),
                        any()
                    )
                } doAnswer { c ->
                    println("answering notifyCharacteristicChanged")
                    connection!!.getNotificationPublishRelay().valueRelay.onNext(Pair(mac, BluetoothGatt.GATT_SUCCESS))
                    true
                }
            }

            connection = getConnection()

            connection.initializeServer(config).timeout(1, TimeUnit.SECONDS).blockingAwait()

            connection.gattServerCallback.onDescriptorWriteRequest(
                device.bluetoothDevice,
                0,
                characteristic.getDescriptor(CLIENT_CONFIG),
                false,
                false,
                0,
                if (isIndication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            val notif= connection.setupNotifications(
                    characteristic,
                    Flowable.just(packets)
                        .flatMap { packet -> packet.writeToStream(GATT_SIZE, scheduler) },
                    isIndication,
                    device
                )
                    .timeout(10, TimeUnit.SECONDS)
                    .doOnNext { b -> println("new bytes $b") }

            ScatterSerializable.parseWrapperFromCRC(parser, notif, ioScheduler)
                .timeout(9, TimeUnit.SECONDS)
        }
    }

    @Test
    fun notifications() {
        for (x in 0..45) {
            val packet = AckPacket.newBuilder(true).setMessage("message").build()
            val newpacket = testNotify(false, packet, AckPacket.parser()).blockingGet() as AckPacket
            assert(newpacket.message == packet.message)
            assert(newpacket.success)
        }
    }

    @Test
    fun indications() {
        for (x in 0..45) {
            val packet = AckPacket.newBuilder(true).setMessage("message").build()
            val newpacket = testNotify(true, packet, AckPacket.parser()).blockingGet() as AckPacket
            assert(newpacket.message == packet.message)
            assert(newpacket.success)
        }
    }
}