package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import dagger.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactoryImpl

@Subcomponent(modules = [GattServerConnectionSubcomponent.GattServerConnectionModule::class])
@GattServerConnectionScope
interface GattServerConnectionSubcomponent {

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun timeoutConfiguration(timeoutConfiguration: TimeoutConfiguration): Builder
        fun build(): GattServerConnectionSubcomponent
    }

    @Module(subcomponents = [ServerTransactionSubcomponent::class])
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

    fun connection(): GattServerConnection
}