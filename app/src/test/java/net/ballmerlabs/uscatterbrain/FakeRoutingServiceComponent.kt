package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.p2p.WifiP2pManager
import android.os.PowerManager
import androidx.room.Room
import com.polidea.rxandroidble2.RxBleClient
import dagger.*
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProvider
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProviderImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapperImpl
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.InetAddress
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
@Component(modules = [FakeRoutingServiceComponent.FakeRoutingServiceModule::class])
interface FakeRoutingServiceComponent {
    object NamedSchedulers {
        const val DATABASE = "executor_database"
        const val BLE_CLIENT = "scheduler-ble-client"
        const val BLE_SERVER = "scheduler-ble-server"
        const val OPERATIONS = "wifi-direct-operations"
    }

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun applicationContext(context: Context): Builder

        @BindsInstance
        fun wifiP2pManager(wifiP2pManager: WifiP2pManager): Builder

        @BindsInstance
        fun wifiDirectBroadcastReceiver(wifiDirectBroadcastReceiver: MockWifiDirectBroadcastReceiver): Builder

        fun build(): FakeRoutingServiceComponent?
    }

    @Module(subcomponents = [FakeWifiDirectInfoSubcomponent::class, FakeBootstrapRequestSubcomponent::class])
    abstract class FakeRoutingServiceModule {
        @Binds
        @Singleton
        abstract fun bindsRoutingServiceBackend(impl: RoutingServiceBackendImpl): RoutingServiceBackend

        @Binds
        @Singleton
        abstract fun bindScatterbrainScheduler(impl: ScatterbrainSchedulerImpl): ScatterbrainScheduler

        @Binds
        @Singleton
        abstract fun bindDatastore(impl: MockScatterbrainDatastore): ScatterbrainDatastore

        @Binds
        @Singleton
        abstract fun bindWifiDirectRadioModule(impl: WifiDirectRadioModuleImpl): WifiDirectRadioModule

        @Binds
        @Singleton
        abstract fun bindRouterPreferences(impl: RouterPreferencesImpl): RouterPreferences

        @Binds
        @Singleton
        abstract fun bindsDatastoreImportProvider(impl: DatastoreImportProviderImpl): DatastoreImportProvider

        @Binds
        @Singleton
        abstract fun bindRadioModuleInternal(impl: BluetoothLERadioModuleImpl): BluetoothLEModule

        @Binds
        @Singleton
        abstract fun provideWifiDirectBroadcastReceiver(impl: MockWifiDirectBroadcastReceiver): WifiDirectBroadcastReceiver

        @Binds
        @Singleton
        abstract fun bindsFirebaseWrapper(impl: MockFirebaseWrapper): FirebaseWrapper

        @Module
        companion object {
            @Provides
            @JvmStatic
            @Singleton
            fun provideDatastore(ctx: Context?): Datastore {
                return Room.databaseBuilder(ctx!!, Datastore::class.java, DATABASE_NAME)
                        .fallbackToDestructiveMigration()
                        .build()
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesBootstrapRequestbuilder(builder: FakeBootstrapRequestSubcomponent.Builder): BootstrapRequestSubcomponent.Builder {
                return builder
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesWifiDirectSubcomponent(builder: FakeWifiDirectInfoSubcomponent.Builder): WifiDirectInfoSubcomponent.Builder {
                return builder
            }


            @Provides
            @JvmStatic
            @Singleton
            fun providesChannel(ctx: Context?, wifiP2pManager: WifiP2pManager?): WifiP2pManager.Channel {
                return mock {  }
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.DATABASE)
            fun provideDatabaseScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory())
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.OPERATIONS)
            fun provideWifiDirectOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory())
            }

            @Provides
            @JvmStatic
            @Singleton
            fun provideRxBleClient(ctx: Context?): RxBleClient {
                return RxBleClient.create(ctx!!)
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_CLIENT)
            fun provideBleClientScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory())
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_SERVER)
            fun provideBleServerScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory())
            }

            @Provides
            @JvmStatic
            fun provideLeAdvertiser(): BluetoothLeAdvertiser {
                return BluetoothAdapter.getDefaultAdapter().bluetoothLeAdvertiser
            }

            @Provides
            @JvmStatic
            fun providesSharedPreferences(context: Context?): SharedPreferences {
                return mock()
            }

            @Provides
            @JvmStatic
            fun providesPowerManager(context: Context?): PowerManager {
                return context!!.getSystemService(Context.POWER_SERVICE) as PowerManager
            }
        }

    }

    fun scatterRoutingService(): RoutingServiceBackend?
    fun wifiDirectModule(): WifiDirectRadioModule?
    fun bootstrapSubcomponent(): Provider<BootstrapRequestSubcomponent.Builder>
    fun inject(provider: DatastoreImportProviderImpl?)

    companion object {
        const val SHARED_PREFS = "scatterbrainprefs"
    }
}