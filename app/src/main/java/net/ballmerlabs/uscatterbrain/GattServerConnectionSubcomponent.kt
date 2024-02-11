package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.content.Context
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import dagger.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLEServerConnectionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLeServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactoryImpl
import javax.inject.Singleton

@Subcomponent(modules = [GattServerConnectionSubcomponent.GattServerConnectionModule::class])
@GattServerConnectionScope
interface GattServerConnectionSubcomponent {

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun timeoutConfiguration(timeoutConfiguration: TimeoutConfiguration): Builder
        fun build(): GattServerConnectionSubcomponent
    }

    @Module(subcomponents = [
        ServerTransactionSubcomponent::class,
        ScatterbrainTransactionSubcomponent::class,
    ])
    abstract class GattServerConnectionModule {
        @Binds
        @GattServerConnectionScope
        abstract fun bindServerConnection(impl: GattServerConnectionImpl): GattServerConnection

        @Binds
        @GattServerConnectionScope
        abstract fun bindsServerState(impl: ServerStateImpl): ServerState

        @Binds
        @GattServerConnectionScope
        abstract fun bindTransactionFactory(impl: ServerTransactionFactoryImpl): ServerTransactionFactory

        @Binds
        @GattServerConnectionScope
        abstract fun bindCachedConnection(impl: CachedLEServerConnectionImpl): CachedLeServerConnection

        @Binds
        @GattServerConnectionScope
        abstract fun bindsTransactionFactory(impl: ScatterbrainTransactionFactoryImpl): ScatterbrainTransactionFactory

        @Module
        companion object {
            @Provides
            @JvmStatic
            @GattServerConnectionScope
            @Throws(SecurityException::class)
            fun providesGattServer(
                    bluetoothManager: BluetoothManager,
                    context: Context,
                    gattServerCallback: GattServerConnection
            ): BluetoothGattServer {
                return bluetoothManager.openGattServer(
                        context,
                        gattServerCallback.gattServerCallback
                    )
            }
        }
    }

    fun transaction(): ScatterbrainTransactionSubcomponent.Builder

    fun connection(): GattServerConnection
    fun cachedConnection(): CachedLeServerConnection
    fun factory(): ScatterbrainTransactionFactory
}