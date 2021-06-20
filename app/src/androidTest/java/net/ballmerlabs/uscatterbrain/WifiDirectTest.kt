package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Parcel
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.Companion.SHARED_PREFS
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.migration.Migrate6
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException

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

        database = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
                .addMigrations(Migrate6())
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

    @Test
    @Throws(TimeoutException::class)
    fun testHack() {
        val pass = "fmefthisisahorriblepassphrase"
        val name = "DIRECT-fmoo"
        val fakeConfig = FakeWifiP2pConfig(
                passphrase = pass,
                networkName = name
        )

        val parcel = Parcel.obtain()
        parcel.writeString(WifiP2pConfig::class.java.name)
        fakeConfig.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val config = parcel.readParcelable<WifiP2pConfig>(WifiP2pConfig::class.java.classLoader)!!
        Log.e("debug", config.toString())
    }
}