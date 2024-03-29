package net.ballmerlabs.uscatterbrain

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.polidea.rxandroidble2.RxBleClient
import dagger.*
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.RoutingServiceModule
import net.ballmerlabs.uscatterbrain.db.*
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProvider
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProviderImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapperImpl
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = RouterPreferences.PREF_NAME)

@Singleton
@Component(modules = [RoutingServiceModule::class])
interface RoutingServiceComponent {
    object NamedSchedulers {
        const val DATABASE = "executor_database"
        const val BLE_CLIENT = "scheduler-ble-client-"
        const val BLE_CALLBACKS = "ble-server-callbacks"
        const val BLE_SERVER = "scheduler-ble-server-"
        const val COMPUTATION = "computation-2"
        const val GLOBAL_IO = "global-io"
        const val MAIN_THREAD = "themainthread"
        const val WIFI_READ = "wifi-read"
        const val WIFI_WRITE = "wifi-write"
    }

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun applicationContext(context: Context): Builder?
        fun build(): RoutingServiceComponent?
    }

    @Module(subcomponents = [
        WifiDirectInfoSubcomponent::class,
        BootstrapRequestSubcomponent::class,
        GattServerConnectionSubcomponent::class,
    ])
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
        abstract fun bindRouterPreferences(impl: RouterPreferencesImpl): RouterPreferences

        @Binds
        @Singleton
        abstract fun bindsDatastoreImportProvider(impl: DatastoreImportProviderImpl): DatastoreImportProvider

        @Binds
        @Singleton
        abstract fun bindWifiDirectRadioModule(impl: WifiDirectRadioModuleImpl): WifiDirectRadioModule

        @Binds
        @Singleton
        abstract fun provideWifiDirectBroadcastReceiver(impl: WifiDirectBroadcastReceiverImpl): WifiDirectBroadcastReceiver

        @Binds
        @Singleton
        abstract fun bindsFirebaseWrapper(impl: FirebaseWrapperImpl): FirebaseWrapper

        @Binds
        @Singleton
        abstract fun bindsServerSocketManager(impl: ServerSocketManagerImpl): ServerSocketManager

        @Binds
        @Singleton
        abstract fun bindsSocketProvider(impl: SocketProviderImpl): SocketProvider

        @Binds
        @Singleton
        abstract fun bindGattServer(impl: GattServerImpl): GattServer

        @Binds
        @Singleton
        abstract fun bindsScanBroadcastReceiver(impl: ScanBroadcastReceiverImpl): ScanBroadcastReceiver

        @Binds
        @Singleton
        abstract fun bindsAdvertiser(impl: AdvertiserImpl): Advertiser

        @Binds
        @Singleton
        abstract fun bindsLeState(impl: LeStateImpl): LeState

        @Binds
        @Singleton
        abstract fun bindsWifiDirectProvider(impl: WifiDirectProviderImpl): WifiDirectProvider

        @Binds
        @Singleton
        abstract fun bindsWakelockManager(impl: WakeLockProviderImpl): WakeLockProvider

        @Module
        companion object {
            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.WIFI_READ)
            fun providesWifiReadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_READ))
            }


            @Provides
            @JvmStatic
            @Singleton
            fun providesAlarmManager(context: Context): AlarmManager {
                return context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            }

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
            @Named(NamedSchedulers.WIFI_WRITE)
            fun providesWifiWriteScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_WRITE))
            }
            @Provides
            @JvmStatic
            @Singleton
            fun providesDatabaseFile(datastore:Datastore): File {
                return File(datastore.openHelper.readableDatabase.path!!)
            }

            @Provides
            fun providesBluetoothManager(ctx: Context): BluetoothManager {
                return ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.COMPUTATION)
            fun providesComputationScheduler(): Scheduler {
                return RxJavaPlugins.createComputationScheduler(ScatterbrainThreadFactory(NamedSchedulers.COMPUTATION))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.DATABASE)
            fun provideDatabaseScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(NamedSchedulers.DATABASE))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.GLOBAL_IO)
            fun provideWifiDirectOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(NamedSchedulers.GLOBAL_IO))
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesWifiManager(context: Context): WifiManager {
                return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_CLIENT))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_CALLBACKS)
            fun providesBleCallbacksScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_CALLBACKS))
            }
            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.MAIN_THREAD)
            fun providesMainThreadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("fakemain"))
            }


            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_SERVER)
            fun provideBleServerScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_SERVER))
            }


            @Provides
            @JvmStatic
            fun providesSharedPreferences(context: Context?): DataStore<Preferences> {
                return context!!.dataStore
            }

            @Provides
            @JvmStatic
            fun providesPowerManager(context: Context?): PowerManager {
                return context!!.getSystemService(Context.POWER_SERVICE) as PowerManager
            }
        }
        
    }

    fun scatterRoutingService(): RoutingServiceBackend
    fun inject(provider: DatastoreImportProviderImpl?)
    fun inject(provider: ScanBroadcastReceiverImpl)
    fun gattConnectionBuilder(): GattServerConnectionSubcomponent.Builder
    fun inject(prover: LuidRandomizeReceiver)

    companion object {
        const val SHARED_PREFS = "scatterbrainprefs"
    }
}