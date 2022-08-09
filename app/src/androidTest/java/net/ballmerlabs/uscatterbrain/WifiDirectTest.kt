package net.ballmerlabs.uscatterbrain

import android.Manifest
import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Parcel
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.rule.GrantPermissionRule
import com.google.firebase.FirebaseApp
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.Companion.SHARED_PREFS
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.RouterPreferencesImpl
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastoreImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class WifiDirectTest {


    @get:Rule
    val wifiDirectGrantRule: GrantPermissionRule = GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private lateinit var radioModule: WifiDirectRadioModule
    private lateinit var datastore: ScatterbrainDatastore
    private lateinit var broadcastReceiver: WifiDirectBroadcastReceiver
    private lateinit var ctx: Context
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory())
    private lateinit var database: Datastore


    @Before
    fun init() {
        ctx = ApplicationProvider.getApplicationContext()
        FirebaseApp.initializeApp(ctx)
        val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(ctx, ctx.mainLooper, null)
        broadcastReceiver = WifiDirectBroadcastReceiverImpl(
                manager,
                channel,
                scheduler
        )

        database = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
                .fallbackToDestructiveMigration()
                .build()

        val prefs = RouterPreferencesImpl(ctx.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE))
        datastore = ScatterbrainDatastoreImpl(
                ctx,
                database,
                scheduler,
                prefs
        )
        radioModule = DaggerRoutingServiceComponent.builder()
                .applicationContext(ctx)
                ?.build()!!.wifiDirectRadioModule()
        radioModule.registerReceiver()
    }

    @Test
    @Throws(TimeoutException::class)
    fun createGroupTest() {
        assert(radioModule.createGroup().timeout(20, TimeUnit.SECONDS)
                .blockingGet().role == BluetoothLEModule.ConnectionRole.ROLE_UKE)
    }

    @Test
    @Throws(TimeoutException::class)
    fun multipleCreateGroup() {
        for (x in 0..5) {
            assert(radioModule.createGroup()
                    .timeout(20, TimeUnit.SECONDS)
                    .blockingGet().role == BluetoothLEModule.ConnectionRole.ROLE_UKE)
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun multipleRemoveGroup() {
        for (x in 1..5) {
            radioModule.removeGroup(retries = 4, delay = 1).blockingAwait()
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun wifiDirectIsUsable() {
        for (x in 0..10) {
            assert(radioModule.wifiDirectIsUsable().timeout(60, TimeUnit.SECONDS).blockingGet())
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun wifiDirectIsUsableAfterCreate() {
        val res = radioModule.createGroup().blockingGet()
        assert(radioModule.wifiDirectIsUsable().timeout(20, TimeUnit.SECONDS).blockingGet())
    }

    @Test
    @Throws(TimeoutException::class)
    fun createAndRemoveGroup() {
        for (x in 0..20) {
            radioModule.removeGroup()
                .timeout(10, TimeUnit.SECONDS)
                .blockingAwait()
            assert(radioModule.createGroup()
                    .timeout(10, TimeUnit.SECONDS)
                    .blockingGet().role == BluetoothLEModule.ConnectionRole.ROLE_UKE)
        }
    }

    @Test
    @Throws(TimeoutException::class)
    fun testHack() {
        val pass = "fmefthisisahorriblepassphrase"
        val name = "DIRECT-fmoo"
        val fakeConfig = FakeWifiP2pConfigImpl(
                passphrase = pass,
                networkName = name,
                wpsInfo = WpsInfo()
        )

        val parcel = Parcel.obtain()
        parcel.writeString(WifiP2pConfig::class.java.name)
        fakeConfig.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val config = parcel.readParcelable<WifiP2pConfig>(WifiP2pConfig::class.java.classLoader)!!
        Log.e("debug", config.toString())
    }
}