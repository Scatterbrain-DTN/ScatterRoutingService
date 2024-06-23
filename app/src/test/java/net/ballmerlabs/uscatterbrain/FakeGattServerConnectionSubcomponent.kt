package net.ballmerlabs.uscatterbrain

import android.bluetooth.BluetoothGattServer
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import dagger.*
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLEServerConnectionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.CachedLeServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnection
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.GattServerConnectionImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerOperationsProvider
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerState
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.ServerStateImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerConnectionOperationQueue
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerOperationQueueImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.operations.ServerOperationsProviderImpl
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactory
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.server.transactions.ServerTransactionFactoryImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl

@Subcomponent(modules = [FakeGattServerConnectionSubcomponent.GattServerConnectionModule::class])
@GattServerConnectionScope
interface FakeGattServerConnectionSubcomponent: GattServerConnectionSubcomponent {

    @Subcomponent.Builder
    interface Builder : GattServerConnectionSubcomponent.Builder {

        @BindsInstance
        fun gattServer(bluetoothGattServer: BluetoothGattServer): Builder

        @BindsInstance
        override fun timeoutConfiguration(timeoutConfiguration: TimeoutConfiguration): Builder

        override fun build(): FakeGattServerConnectionSubcomponent
    }

    @Module(subcomponents = [
        FakeServerTransactionSubcomponent::class,
        FakeTransactionSubcomponent::class,
    ])
    abstract class GattServerConnectionModule {
        @Binds
        @GattServerConnectionScope
        abstract fun bindServerConnection(impl: GattServerConnectionImpl): GattServerConnection

        @Binds
        @GattServerConnectionScope
        abstract fun bindOperationsProvider(impl: ServerOperationsProviderImpl): ServerOperationsProvider

        @Binds
        @GattServerConnectionScope
        abstract fun bindOperationQueue(impl: ServerOperationQueueImpl): ServerConnectionOperationQueue

        @Binds
        @GattServerConnectionScope
        abstract fun bindsServerState(impl: ServerStateImpl): ServerState

        @Binds
        @GattServerConnectionScope
        abstract fun bindsTransactionFactory(impl: ScatterbrainTransactionFactoryImpl): ScatterbrainTransactionFactory

        @Binds
        @GattServerConnectionScope
        abstract fun bindTransactionFactory(impl: ServerTransactionFactoryImpl): ServerTransactionFactory

        @Binds
        @GattServerConnectionScope
        abstract fun bindCachedServer(impl: CachedLEServerConnectionImpl): CachedLeServerConnection

        @Binds
        @GattServerConnectionScope
        abstract fun bindFakeBuilder(builder: FakeTransactionSubcomponent.Builder): ScatterbrainTransactionSubcomponent.Builder

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

    override fun connection(): GattServerConnectionImpl

    override fun transaction(): FakeTransactionSubcomponent.Builder
}