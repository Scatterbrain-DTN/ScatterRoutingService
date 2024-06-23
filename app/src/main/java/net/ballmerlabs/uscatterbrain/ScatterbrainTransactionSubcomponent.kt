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
            fun providesTransactionFinalizer(
                @Named(NamedSchedulers.TRANS_IO) transIo: Scheduler,
                @Named(NamedSchedulers.BLE_PARSE) bleParse: Scheduler,
                @Named(NamedSchedulers.BLE_WRITE) bleWrite: Scheduler,
                @Named(NamedSchedulers.BLE_READ) bleRead: Scheduler,
            ): TransactionFinalizer {
                return object : TransactionFinalizer {
                    override fun onFinalize() {
                        transIo.shutdown()
                        bleParse.shutdown()
                        bleWrite.shutdown()
                        bleRead.shutdown()
                    }
                }
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

    fun wifiImpl(): WifiDirectRadioModuleImpl
    fun connection(): CachedLeConnection

    @Named(NamedSchedulers.TRANS_IO)
    fun transIo(): Scheduler

    @Named(NamedSchedulers.BLE_WRITE)
    fun bleWrite(): Scheduler

    @Named(NamedSchedulers.BLE_READ)
    fun bleRead(): Scheduler

    @Named(NamedSchedulers.BLE_PARSE)
    fun bleParse(): Scheduler

    fun device(): RxBleDevice

    fun luid(): UUID
}


interface TransactionFinalizer {
    fun onFinalize()
}