package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import dagger.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.*

import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactoryImpl

@Subcomponent(modules = [FakeGattServerConnectionSubcomponent.GattServerConnectionModule::class])
@GattServerConnectionScope
interface FakeGattServerConnectionSubcomponent: GattServerConnectionSubcomponent {

    @Subcomponent.Builder
    interface Builder: GattServerConnectionSubcomponent.Builder {

        @BindsInstance
        fun gattServer(bluetoothGattServer: BluetoothGattServer): Builder

        override fun build(): GattServerConnectionSubcomponent
    }

    @Module(subcomponents = [FakeServerTransactionSubcomponent::class])
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
            fun providesTransactionBuilder(builder: FakeServerTransactionSubcomponent.Builder): ServerTransactionSubcomponent.Builder {
                return builder
            }
        }
    }

    override fun connection(): GattServerConnection
}