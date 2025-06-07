package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.geeksville.mesh.DataPacket
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.mock.DaggerFakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.mock.FakeRoutingServiceComponent
import net.ballmerlabs.uscatterbrain.mock.meshtastic.FakeMeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.mock.meshtastic.MockMeshtasticConnection
import net.ballmerlabs.uscatterbrain.mock.util.MockRouterPreferences
import net.ballmerlabs.uscatterbrain.mock.util.mockLoggerGenerator
import net.ballmerlabs.uscatterbrain.util.logger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MeshtasticConnectionTest {

    lateinit var firstSubcomponent: FakeMeshtasticConnectionSubcomponent
    lateinit var secondSubcomponent: FakeMeshtasticConnectionSubcomponent

    init {
        logger = mockLoggerGenerator
        System.setProperty("jna.library.path", "/opt/homebrew/lib")
    }

    val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))


    val firstBroadcastReceiverState = MeshtasticBroadcastReceiverStateImpl(scheduler)

    val secondBroadcastReceiverState = MeshtasticBroadcastReceiverStateImpl(scheduler)


    fun buildModule(local: MeshtasticBroadcastReceiverState, remote: MeshtasticBroadcastReceiverState): FakeMeshtasticConnectionSubcomponent {
        return (DaggerFakeRoutingServiceComponent.builder()
            .applicationContext(mock {  })
            .wifiP2pManager(mock { })
            .rxBleClient(mock { })
            .packetOutputStream(java.io.ByteArrayOutputStream())
            .packetInputStream(java.io.ByteArrayInputStream(byteArrayOf()))
            .wifiDirectBroadcastReceiver(
                net.ballmerlabs.uscatterbrain.mock.network.wifidirect.MockWifiDirectBroadcastReceiver(
                    mock { })
            )
            .mockPreferences(MockRouterPreferences())
            .bluetoothManager(mock {  })
            .wifiManager(mock { })
            .build()!!
            .meshtasticConnectionBuilder()
            .broadcastReceiverState(local)
            .remoteState(remote)
            .service(mock {  })
            .build() as FakeMeshtasticConnectionSubcomponent?)!!
    }

    @Before
    fun init() {
        MockitoAnnotations.openMocks(this)
        firstSubcomponent = buildModule(firstBroadcastReceiverState, secondBroadcastReceiverState)
        secondSubcomponent = buildModule(secondBroadcastReceiverState, firstBroadcastReceiverState)
    }

    @After
    fun cleanup() {
        Mockito.validateMockitoUsage()
    }


    @Test
    fun packetTest() {
        val firstconnection = firstSubcomponent.radioModule()
        val secondconnection = secondSubcomponent.radioModule()

        val firstSession = firstconnection.startSession("first")
        val secondSession = secondconnection.startSession("second")
        firstSession.state().handshake()
            .mergeWith(firstconnection.handlePackets())
            .mergeWith( secondSession.state().handshake()
                .mergeWith(secondconnection.handlePackets()))
            .blockingAwait()

    }

}