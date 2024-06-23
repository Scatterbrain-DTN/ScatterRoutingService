package net.ballmerlabs.uscatterbrain

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.polidea.rxandroidble2.RxBleClient
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent.RoutingServiceModule
import net.ballmerlabs.uscatterbrain.db.DATABASE_NAME
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.RouterPreferencesImpl
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastoreImpl
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProvider
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProviderImpl
import net.ballmerlabs.uscatterbrain.db.migration.Migrate21
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.Advertiser
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.AdvertiserImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LeStateImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LuidRandomizeReceiver
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.LuidRandomizeWorker
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.ScanBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.ScanBroadcastReceiverImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerImpl
import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopApiSubcomponent
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManager
import net.ballmerlabs.uscatterbrain.network.wifidirect.ServerSocketManagerImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.SocketProvider
import net.ballmerlabs.uscatterbrain.network.wifidirect.SocketProviderImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiverImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapperImpl
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Named
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = RouterPreferences.PREF_NAME)

@Singleton
@Component(modules = [RoutingServiceModule::class])
interface RoutingServiceComponent {
    object NamedSchedulers {
        const val DATABASE = "executor_database"
        const val BLE_CLIENT = "sb-ble-client"
        const val BLE_CALLBACKS = "bleserver-callbacks"
        const val BLE_SERVER = "sb-ble-server"
        const val COMPUTATION = "computation-2"
        const val WIFI_SERVER = "wifi-server"
        const val MAIN_THREAD = "themainthread"
        const val TIMEOUT = "global-timeout"
        const val BLE_ADVERTISE = "ble-advertise"
        const val WIFI_CONNECT = "wifi-connect"
    }

    object NamedExecutors {
        const val SERVER_INTERACTION = "server-executor"
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
        WifiGroupSubcomponent::class,
        DesktopApiSubcomponent::class
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
        abstract fun bindWifiDirectRadioModule(impl: WifiDirectRadioModuleImpl): WifiDirectRadioModule

        @Binds
        @Singleton
        abstract fun bindRouterPreferences(impl: RouterPreferencesImpl): RouterPreferences

        @Binds
        @Singleton
        abstract fun bindsDatastoreImportProvider(impl: DatastoreImportProviderImpl): DatastoreImportProvider

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

        @Binds
        @Singleton
        abstract fun bindsBroadcaster(impl: BroadcasterImpl): Broadcaster

        @Module
        companion object {



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
                        .addMigrations(Migrate21())
                        .fallbackToDestructiveMigration()
                        .build()
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesDatabaseFile(datastore:Datastore): File {
                return File(datastore.openHelper.readableDatabase.path!!)
            }

            @Provides
            @JvmStatic
            @Singleton
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
                return RxJavaPlugins.createComputationScheduler(ScatterbrainThreadFactory(NamedSchedulers.DATABASE))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_ADVERTISE)
            fun providesAdvertiseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_ADVERTISE))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.WIFI_SERVER)
            fun provideWifiDirectOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_SERVER))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.WIFI_CONNECT)
            fun provideWifiDirectConnectSchedule(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_CONNECT))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.TIMEOUT)
            fun provideGlobalTimeoutScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(NamedSchedulers.TIMEOUT))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedExecutors.SERVER_INTERACTION)
            fun provideServerExecutor(): ExecutorService {
                return Executors.newSingleThreadExecutor()
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
            fun providesConnectivityManager(context: Context): ConnectivityManager {
                return context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            @Named(NamedSchedulers.MAIN_THREAD)
            fun providesMainThreadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("fakemain"))
            }


            @Provides
            @JvmStatic
            @Singleton
            @Named(NamedSchedulers.BLE_SERVER)
            fun provideBleServerScheduler(@Named(NamedExecutors.SERVER_INTERACTION) executor: ExecutorService): Scheduler {
                return Schedulers.from(executor)
            }


            @Provides
            @JvmStatic
            @Singleton
            fun providesSharedPreferences(context: Context?): DataStore<Preferences> {
                return context!!.dataStore
            }

            @Provides
            @JvmStatic
            @Singleton
            fun providesPowerManager(context: Context?): PowerManager {
                return context!!.getSystemService(Context.POWER_SERVICE) as PowerManager
            }
        }
        
    }

    fun scatterRoutingService(): RoutingServiceBackend
    fun inject(provider: DatastoreImportProviderImpl?)
    fun inject(provider: ScanBroadcastReceiverImpl)
    fun inject(provider: LuidRandomizeWorker)
    fun gattConnectionBuilder(): GattServerConnectionSubcomponent.Builder
    fun inject(prover: LuidRandomizeReceiver)

    companion object {
        const val SHARED_PREFS = "scatterbrainprefs"
    }
}