package net.ballmerlabs.uscatterbrain

import dagger.Binds
import dagger.Module
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl

@ScatterbrainTransactionScope
@Subcomponent(modules = [ScatterbrainTransactionSubcomponent.ScatterbrainTransactionModule::class])
interface ScatterbrainTransactionSubcomponent {

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
    }

    fun bluetoothLeRadioModule(): BluetoothLEModule
}