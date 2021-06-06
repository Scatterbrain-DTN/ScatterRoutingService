package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.Companion.SHARED_PREFS
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiverImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class WifiDirectTest {

    private lateinit var radioModule: WifiDirectRadioModule
    private lateinit var datastore: ScatterbrainDatastore
    private lateinit var broadcastReceiver: WifiDirectBroadcastReceiver
    private lateinit var ctx: Context
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory())
    private lateinit var database: Datastore


    @Before
    fun init() {
        ctx = ApplicationProvider.getApplicationContext()
        val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(ctx, ctx.mainLooper, null)
        broadcastReceiver = WifiDirectBroadcastReceiverImpl(
                manager,
                channel,
                scheduler
        )

        database = Room.databaseBuilder(ctx, Datastore::class.java, DATABASE_NAME)
                .build()

        val prefs = RouterPreferencesImpl(ctx.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE))
        datastore = ScatterbrainDatastoreImpl(
                ctx,
                database,
                scheduler,
                prefs
        )
        radioModule = WifiDirectRadioModuleImpl(
                manager,
                ctx,
                datastore,
                prefs,
                scheduler,
                channel,
                broadcastReceiver
        )
        radioModule.registerReceiver()
    }

    @Test
    @Throws(TimeoutException::class)
    fun createGroupTest() {
        assert(radioModule.createGroup().blockingGet().role == BluetoothLEModule.ConnectionRole.ROLE_UKE)
    }

}