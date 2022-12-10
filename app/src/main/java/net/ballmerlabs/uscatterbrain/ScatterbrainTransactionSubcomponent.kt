package net.ballmerlabs.uscatterbrain

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl
import javax.inject.Named

@ScatterbrainTransactionScope
@Subcomponent(modules = [ScatterbrainTransactionSubcomponent.ScatterbrainTransactionModule::class])
interface ScatterbrainTransactionSubcomponent {

    object NamedSchedulers {
        const val WIFI_READ = "wifi-read"
        const val WIFI_WRITE = "wifi-write"
    }

    @Subcomponent.Builder
    interface Builder {
        fun build(): ScatterbrainTransactionSubcomponent
    }

    @Module
    abstract class ScatterbrainTransactionModule {
        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindWifiDirectRadioModule(impl: WifiDirectRadioModuleImpl): WifiDirectRadioModule

        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindRadioModuleInternal(impl: BluetoothLERadioModuleImpl): BluetoothLEModule

        @Module
        companion object {

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



    fun bluetoothLeRadioModule(): BluetoothLEModule
}