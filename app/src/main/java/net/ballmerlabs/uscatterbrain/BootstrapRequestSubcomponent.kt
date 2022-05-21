package net.ballmerlabs.uscatterbrain

import android.os.Bundle
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import javax.inject.Named


@Subcomponent(modules = [BootstrapRequestSubcomponent.BootstrapRequestDaggerModule::class])
@BootstrapRequestScope
interface BootstrapRequestSubcomponent {

    companion object {
        const val NAME = "bootstrapname"
        const val PASSPHRASE = "bootstrappassphrase"
    }

    data class WifiDirectBootstrapRequestArgs(
            val name: String,
            val passphrase: String,
            val role: BluetoothLEModule.ConnectionRole
    )

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun wifiDirectArgs(args: WifiDirectBootstrapRequestArgs): Builder
        fun build(): BootstrapRequestSubcomponent?
    }

    @Module
    abstract class BootstrapRequestDaggerModule {
        @Module
        companion object {
            @Provides
            @JvmStatic
            @BootstrapRequestScope
            fun providesBundleExtras(): Bundle {
                return Bundle()
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(NAME)
            fun providesName(args: WifiDirectBootstrapRequestArgs): String {
                return args.name
            }
            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(PASSPHRASE)
            fun providesPassphrase(args: WifiDirectBootstrapRequestArgs): String {
                return args.passphrase
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            fun providesRole(args: WifiDirectBootstrapRequestArgs): BluetoothLEModule.ConnectionRole {
                return args.role
            }


        }
    }


    fun wifiBootstrapRequest(): WifiDirectBootstrapRequest
    fun bootstrapRequest(): BootstrapRequest


}