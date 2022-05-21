package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.p2p.WifiP2pManager
import android.os.PowerManager
import androidx.room.Room
import com.polidea.rxandroidble2.RxBleClient
import dagger.*
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.RoutingServiceModule
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
import javax.inject.Named
import javax.inject.Singleton

@Singleton
@Component(modules = [RoutingServiceModule::class])
interface RoutingServiceComponent {
    object NamedSchedulers {
        const val DATABASE = "executor_database"
        const val BLE_CLIENT = "scheduler-ble-client"
        const val BLE_SERVER = "scheduler-ble-server"
        const val OPERATIONS = "wifi-direct-operations"
    }

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun applicationContext(context: Context): Builder?
        fun build(): RoutingServiceComponent?
    }

    @Module(subcomponents = [WifiDirectInfoSubcomponent::class, BootstrapRequestSubcomponent::class])
    abstract class RoutingServiceModule {
        @Binds
        @Singleton
        abstract fun bindsRoutingServiceBackend(impl: RoutingServiceBackendImpl): RoutingServiceBackend

        @Binds
        @Singleton
        abstract fun bindScatterbrainScheduler(impl: ScatterbrainSchedulerImpl): ScatterbrainScheduler

        @Binds
        @Singleton
        abstract fun bindDatastore(impl: ScatterbrainDatastoreImpl): ScatterbrainDatastore

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
        abstract fun provideWifiDirectBroadcastReceiver(impl: WifiDirectBroadcastReceiverImpl): WifiDirectBroadcastReceiver

        @Binds
        @Singleton
        abstract fun bindsFirebaseWrapper(impl: FirebaseWrapperImpl): FirebaseWrapper

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
            fun providesChannel(ctx: Context?, wifiP2pManager: WifiP2pManager?): WifiP2pManager.Channel {
                return wifiP2pManager!!.initialize(ctx, ctx!!.mainLooper, null)
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesWifiP2pManager(ctx: Context?): WifiP2pManager {
                return ctx!!.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
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
                return context!!.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
            }

            @Provides
            @JvmStatic
            fun providesPowerManager(context: Context?): PowerManager {
                return context!!.getSystemService(Context.POWER_SERVICE) as PowerManager
            }
        }
        
    }

    fun scatterRoutingService(): RoutingServiceBackend?
    fun wifiDirectRadioModule(): WifiDirectRadioModule
    fun inject(provider: DatastoreImportProviderImpl?)

    companion object {
        const val SHARED_PREFS = "scatterbrainprefs"
    }
}