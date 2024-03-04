package net.ballmerlabs.uscatterbrain

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLEConnectionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLeConnection
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl
import javax.inject.Named
import javax.inject.Singleton

@ScatterbrainTransactionScope
@Subcomponent(modules = [FakeTransactionSubcomponent.ScatterbrainTransactionModule::class])
interface FakeTransactionSubcomponent: ScatterbrainTransactionSubcomponent {
    object NamedSchedulers {
        const val WIFI_READ = "wifi-read"
        const val WIFI_WRITE = "wifi-write"
    }

    @Subcomponent.Builder
    interface Builder: ScatterbrainTransactionSubcomponent.Builder {
        @BindsInstance
        fun connection(cachedLEConnection: CachedLeConnection): Builder

        override fun build(): ScatterbrainTransactionSubcomponent?
    }

    @Module
    abstract class ScatterbrainTransactionModule {

        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindRadioModuleInternal(impl: BluetoothLERadioModuleImpl): BluetoothLEModule


        @Binds
        @ScatterbrainTransactionScope
        abstract fun wifiDirectRadioModule(wifiDirectRadioModuleImpl: WifiDirectRadioModuleImpl): WifiDirectRadioModule


        @Module
        companion object {
            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_READ)
            fun providesBleReadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(
                    ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_READ))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_PARSE)
            fun providesBleParseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(
                    ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_PARSE))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_WRITE)
            fun providesBleWriteScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(
                    ScatterbrainTransactionSubcomponent.NamedSchedulers.BLE_WRITE))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(ScatterbrainTransactionSubcomponent.NamedSchedulers.TRANS_IO)
            fun providesTransIoScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(
                    ScatterbrainTransactionSubcomponent.NamedSchedulers.TRANS_IO))
            }


            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.WIFI_READ)
            fun providesWifiReadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_READ))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.WIFI_WRITE)
            fun providesWifiWriteScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.WIFI_WRITE))
            }

        }
    }

    override fun connection(): CachedLeConnection
}