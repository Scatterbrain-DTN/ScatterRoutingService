package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.PowerManager
import androidx.room.Room
import com.polidea.rxandroidble2.RxBleClient
import dagger.*
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.db.DATABASE_NAME
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.db.MockScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProvider
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProviderImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServer
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.*
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockFirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.MockRouterPreferences
import org.mockito.kotlin.mock
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
@Component(modules = [FakeRoutingServiceComponent.FakeRoutingServiceModule::class])
interface FakeRoutingServiceComponent {
    @Component.Builder
    interface Builder {
        @BindsInstance
        fun applicationContext(context: Context): Builder

        @BindsInstance
        fun wifiP2pManager(wifiP2pManager: WifiP2pManager): Builder

        @BindsInstance
        fun packetInputStream(inputStream: InputStream): Builder

        @BindsInstance
        fun mockPreferences(routerPreferences: MockRouterPreferences): Builder

        @BindsInstance
        fun packetOutputStream(outputStream: OutputStream): Builder

        @BindsInstance
        fun wifiDirectBroadcastReceiver(wifiDirectBroadcastReceiver: MockWifiDirectBroadcastReceiver): Builder

        @BindsInstance
        fun bluetoothManager(bluetoothManager: BluetoothManager): Builder

        @BindsInstance
        fun wifiManager(wifiManager: WifiManager): Builder

        @BindsInstance
        fun rxBleClient(client: RxBleClient): Builder

        fun build(): FakeRoutingServiceComponent?
    }

    @Module(subcomponents = [
        FakeWifiDirectInfoSubcomponent::class,
        FakeBootstrapRequestSubcomponent::class,
        FakeGattServerConnectionSubcomponent::class,
        FakeTransactionSubcomponent::class
    ])
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
        abstract fun bindRouterPreferences(impl: MockRouterPreferences): RouterPreferences

        @Binds
        @Singleton
        abstract fun bindsDatastoreImportProvider(impl: DatastoreImportProviderImpl): DatastoreImportProvider

        @Binds
        @Singleton
        abstract fun bindsTransactionFactory(impl: ScatterbrainTransactionFactoryImpl): ScatterbrainTransactionFactory

        @Binds
        @Singleton
        abstract fun bindsAdvertiser(impl: AdvertiserImpl): Advertiser

        @Binds
        @Singleton
        abstract fun provideWifiDirectBroadcastReceiver(impl: MockWifiDirectBroadcastReceiver): WifiDirectBroadcastReceiver

        @Binds
        @Singleton
        abstract fun bindsServerSocketManager(impl: MockServerSocketManager): ServerSocketManager

        @Binds
        @Singleton
        abstract fun bindsLeState(impl: LeStateImpl): LeState

        @Binds
        @Singleton
        abstract fun bindSocketProvider(impl: MockSocketProvider): SocketProvider

        @Binds
        @Singleton
        abstract fun bindsFirebaseWrapper(impl: MockFirebaseWrapper): FirebaseWrapper

        @Binds
        @Singleton
        abstract fun bindGattServer(impl: GattServerImpl): GattServer

        @Binds
        @Singleton
        abstract fun bindsManagedServer(impl: ManagedGattServerImpl): ManagedGattServer

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
            @Named("band")
            fun providesBand(): Int {
                return FakeWifiP2pConfig.GROUP_OWNER_BAND_2GHZ
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
            fun providesGattServerBuilder(builder: FakeGattServerConnectionSubcomponent.Builder): GattServerConnectionSubcomponent.Builder {
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
            @Named(RoutingServiceComponent.NamedSchedulers.DATABASE)
            fun provideDatabaseScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-database"))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION)
            fun provideComputeScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-computation"))
            }


            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.IO)
            fun provideWifiDirectOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("test-ops"))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.BLE_CLIENT)
            fun provideBleClientScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-client"))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.BLE_CALLBACKS)
            fun providesBleCallbacksScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-callbacks"))
            }

            @Provides
            @JvmStatic
            @Singleton
            @Named(RoutingServiceComponent.NamedSchedulers.BLE_SERVER)
            fun provideBleServerScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory("test-server"))
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

    fun scatterRoutingService(): RoutingServiceBackend
    fun gattServer(): GattServer
    fun gattConnectionBuilder(): FakeGattServerConnectionSubcomponent.Builder
    fun bootstrapSubcomponent(): Provider<BootstrapRequestSubcomponent.Builder>
    fun getTransactionBuilder(): ScatterbrainTransactionSubcomponent.Builder
    fun inject(provider: DatastoreImportProviderImpl?)

    companion object {
        const val SHARED_PREFS = "scatterbrainprefs"
    }
}