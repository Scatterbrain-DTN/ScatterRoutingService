package net.ballmerlabs.uscatterbrain

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Parcel
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.rule.GrantPermissionRule
import com.google.firebase.FirebaseApp
import com.goterl.lazysodium.BuildConfig
import com.polidea.rxandroidble2.internal.RxBleLog
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import com.polidea.rxandroidble2.mockrxandroidble.RxBleConnectionMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleDeviceMock
import com.polidea.rxandroidble2.mockrxandroidble.RxBleScanRecordMock
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.RouterPreferencesImpl
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastoreImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import net.ballmerlabs.uscatterbrain.util.retryDelay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class WifiDirectTest {


    @JvmField
    @Rule
    val wifiDirectGrantRule: GrantPermissionRule = if(Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
    ) else
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

    private lateinit var radioModule: WifiDirectRadioModuleImpl
    private lateinit var datastore: ScatterbrainDatastore
    private lateinit var broadcastReceiver: WifiDirectBroadcastReceiver
    private lateinit var ctx: Context
    private val scheduler = RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test"))
    private lateinit var database: Datastore
    private lateinit var manager: WifiManager

    @Before
    fun init() {
        ctx = ApplicationProvider.getApplicationContext()
        FirebaseApp.initializeApp(ctx)
        manager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

        database = Room.inMemoryDatabaseBuilder(ctx, Datastore::class.java)
            .fallbackToDestructiveMigration()
            .build()

        val prefs = RouterPreferencesImpl(ctx.dataStore)
        datastore = ScatterbrainDatastoreImpl(
            ctx,
            database,
            scheduler,
            scheduler,
            prefs,
            mock {  },
            mock {  }
        )
        val component = DaggerRoutingServiceComponent.builder()
            .applicationContext(ctx)
            ?.build()!!
        radioModule = component.gattConnectionBuilder()
            .timeoutConfiguration(TimeoutConfiguration(5, TimeUnit.SECONDS, scheduler))
            .build()
            .transaction()
            .device(getBogusRxBleDevice("ff:ff:ff:ff:ff:ff"))
            .luid(UUID.randomUUID())
        .build()!!.wifiImpl()
        radioModule.registerReceiver()
    }

    @Test
    @Throws(TimeoutException::class)
    fun createGroupTest() {
        assert(
            radioModule.createGroupSingle(radioModule.getBand()).timeout(20, TimeUnit.SECONDS)
                .blockingGet().isGroupOwner
        )
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
        RxBleLog.setLogLevel(RxBleLog.VERBOSE)
        radioModule.removeGroup().blockingAwait()
        val res =  radioModule.createGroupSingle(radioModule.getBand()).retryDelay( 5, 10).timeout(60, TimeUnit.SECONDS).blockingGet()
        assert(radioModule.wifiDirectIsUsable().timeout(20, TimeUnit.SECONDS).retryDelay( 5, 10).blockingGet())
        assert(res.groupFormed)
    }

    @Test
    @Throws(TimeoutException::class)
    fun createGroupBands() {
        radioModule.createGroupSingle(FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .timeout(20, TimeUnit.SECONDS)
            .blockingGet().isGroupOwner
        radioModule.createGroupSingle(FakeWifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            .timeout(20, TimeUnit.SECONDS)
            .blockingGet().isGroupOwner
    }

    @Test
    @Throws(TimeoutException::class)
    fun createAndRemoveGroup() {
        radioModule.removeGroup()
            .timeout(10, TimeUnit.SECONDS)
            .onErrorComplete()
            .blockingAwait()
        for (x in 0..20) {
            assert(
                radioModule.createGroupSingle(radioModule.getBand())
                    .timeout(20, TimeUnit.SECONDS)
                    .blockingGet().isGroupOwner
            )
            radioModule.removeGroup()
                .timeout(20, TimeUnit.SECONDS)
                .blockingAwait()
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
            wpsInfo = WpsInfo(),
            suggestedband = FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        )

        val parcel = Parcel.obtain()
        parcel.writeString(WifiP2pConfig::class.java.name)
        fakeConfig.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val config = parcel.readParcelable<WifiP2pConfig>(WifiP2pConfig::class.java.classLoader)!!
        Log.e("debug", config.toString())
    }
}