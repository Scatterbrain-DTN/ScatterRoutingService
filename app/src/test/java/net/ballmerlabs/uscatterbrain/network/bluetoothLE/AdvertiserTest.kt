package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.app.AlarmManager
import android.bluetooth.*
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.Build
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.FakeGattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.FakeRoutingServiceComponent
import net.ballmerlabs.scatterproto.*
import net.ballmerlabs.scatterproto.Optional
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.proto.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.proto.getHashUuid

import net.ballmerlabs.uscatterbrain.network.wifidirect.MockWifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.util.getBogusRxBleDevice
import net.ballmerlabs.uscatterbrain.util.logger
import net.ballmerlabs.uscatterbrain.util.mockLoggerGenerator
import net.ballmerlabs.uscatterbrain.util.toBytes
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*;
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import proto.Scatterbrain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AdvertiserTest {
    init {
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
        logger = mockLoggerGenerator
    }


    private val scheduler = RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-single"))
    private val ioScheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-io"))

    private lateinit var disposable: CompositeDisposable

    @Mock
    private lateinit var manager: BluetoothManager

    @Mock
    private lateinit var alarmmanager: AlarmManager

    private lateinit var preferences: MockRouterPreferences

    private lateinit var advertiser: AdvertiserImpl
    lateinit var leState: MockLeState

    @Mock
    private lateinit var context: Context

    private lateinit var fakeRoutingServiceComponent: FakeRoutingServiceComponent
    private lateinit var fakeGattServerConnection: FakeGattServerConnectionSubcomponent

    init {
        logger = mockLoggerGenerator
    }

    private fun setupModule(): FakeRoutingServiceComponent {
        preferences = MockRouterPreferences()
        val fake =  DaggerFakeRoutingServiceComponent.builder()
            .applicationContext(context)
            .wifiP2pManager(mock { })
            .rxBleClient(mock {  })
            .packetOutputStream(ByteArrayOutputStream())
            .packetInputStream(ByteArrayInputStream(byteArrayOf()))
            .wifiDirectBroadcastReceiver(MockWifiDirectBroadcastReceiver(mock { }))
            .mockPreferences(preferences)
            .bluetoothManager(manager)
            .wifiManager(mock {  })
            .build()!!
        fakeGattServerConnection =  fake.gattConnectionBuilder()
            .gattServer(mock {  })
            .timeoutConfiguration(mock {  })
            .build()
        fakeRoutingServiceComponent = fake
        return fake
    }

    private fun reInit() {
        disposable = CompositeDisposable()
        setupModule()
        leState = MockLeState(
            serverConnection = fakeGattServerConnection,
            connectionFactory = Observable.empty()
        )
        advertiser = AdvertiserImpl(
            context = context,
            firebase = MockFirebaseWrapper(),
            leState = { leState },
            manager = manager,
            wakeLockProvider = mock {  },
            advertiseScheduler = scheduler,
            timeoutScheduler = scheduler,
            wifiDirectBroadcastReceiver = MockWifiDirectBroadcastReceiver(mock())
        )
    }

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        reInit()
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
        disposable.dispose()
    }

    @Test
    fun shrinkUkes() {
        val a: BluetoothAdapter = mock {
            on { leMaximumAdvertisingDataLength } doReturn 20
        }
        manager = mock {
            on { adapter } doReturn a
        }
        reInit()

        val ukes = mutableMapOf<UUID, UpgradePacket>()
        var s = 0.toLong()
        for (x in 0..40) {
            val packet = UpgradePacket.newBuilder(Scatterbrain.Role.UKE)
                .setProvides(Provides.BLE)
                .setSessionID(1)
                .setFrom(UUID.randomUUID())
                .build()!!
            val uuid = UUID.randomUUID()
            s += uuid.toBytes().size
            s += packet.packet.toByteArray().size
            ukes[uuid] = packet
        }
        assert(s > 20)

        val packet = advertiser.shrinkUkes(ukes)
        assert(packet.packet.toByteArray().size < 20)
    }

    @Test
    fun getLuid() {
        val luid = advertiser.getRawLuid()
        val hash = advertiser.getHashLuid()
        assertNotEquals(luid, hash)
        assertEquals(getHashUuid(luid), hash)
    }

    @Test
    fun randomizeAndRemove() {
        val luid = UUID.randomUUID()

        val fakeConnection =  fakeGattServerConnection.transaction()
            .connection(MockCachedLeConnection(
                ioScheduler = ioScheduler,
                bleDevice = getBogusRxBleDevice("ff:ff:ff:ff:ff:ff"),
                state = leState,
                leAdvertiser = advertiser,
                luid = luid,
                channelNotif = InputStreamObserver(8000)
            ))
            .luid(luid)
            .device(mock {  })
            .build()!!

        leState.connectionCache[luid] = fakeConnection
        val hash = advertiser.getHashLuid()
        advertiser.randomizeLuidAndRemove()
        advertiser.isAdvertising.onNext(Pair(Optional.of(mock {  }), AdvertisingSetCallback.ADVERTISE_SUCCESS))
        advertiser.advertisingDataUpdated.onNext(AdvertisingSetCallback.ADVERTISE_SUCCESS)
        advertiser.awaitNotBusy().blockingAwait()
        assertNotEquals(hash, advertiser.getHashLuid())

    }
}