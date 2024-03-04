package net.ballmerlabs.uscatterbrain

import com.polidea.rxandroidble2.RxBleDevice
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
import java.util.UUID
import javax.inject.Named
import javax.inject.Singleton

@ScatterbrainTransactionScope
@Subcomponent(modules = [ScatterbrainTransactionSubcomponent.ScatterbrainTransactionModule::class])
interface ScatterbrainTransactionSubcomponent {

    object NamedSchedulers {

        const val BLE_READ = "ble-read"
        const val BLE_WRITE = "ble-write"
        const val TRANS_IO = "trans-io"
        const val BLE_PARSE = "ble-parse"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun device(device: RxBleDevice): Builder
        @BindsInstance
        fun luid(luid: UUID): Builder
        fun build(): ScatterbrainTransactionSubcomponent?
    }

    @Module
    abstract class ScatterbrainTransactionModule {
        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindRadioModuleInternal(impl: BluetoothLERadioModuleImpl): BluetoothLEModule

        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindsCachedLeConnection(impl: CachedLEConnectionImpl): CachedLeConnection


        @Binds
        @ScatterbrainTransactionScope
        abstract fun bindWifiDirectRadioModule(impl: WifiDirectRadioModuleImpl): WifiDirectRadioModule


        @Module
        companion object {


            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.BLE_READ)
            fun providesBleReadScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_READ))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.BLE_PARSE)
            fun providesBleParseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_PARSE))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.BLE_WRITE)
            fun providesBleWriteScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BLE_WRITE))
            }

            @Provides
            @JvmStatic
            @ScatterbrainTransactionScope
            @Named(NamedSchedulers.TRANS_IO)
            fun providesTransIoScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(NamedSchedulers.TRANS_IO))
            }

        }
    }

    fun bluetoothLeRadioModule(): BluetoothLEModule

    fun wifiDirectRadioModule(): WifiDirectRadioModule
    fun connection(): CachedLeConnection


    fun device(): RxBleDevice
}