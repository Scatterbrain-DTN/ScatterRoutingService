package net.ballmerlabs.uscatterbrain

import android.os.Bundle
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import javax.inject.Named


@Subcomponent(modules = [FakeBootstrapRequestSubcomponent.BootstrapRequestDaggerModule::class])
@BootstrapRequestScope
interface FakeBootstrapRequestSubcomponent : BootstrapRequestSubcomponent {

    @Subcomponent.Builder
    interface Builder: BootstrapRequestSubcomponent.Builder {
        override fun build(): BootstrapRequestSubcomponent?
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
            @Named(BootstrapRequestSubcomponent.NAME)
            fun providesName(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): String {
                return args.name
            }
            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(BootstrapRequestSubcomponent.PASSPHRASE)
            fun providesPassphrase(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): String {
                return args.passphrase
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            fun providesRole(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): BluetoothLEModule.ConnectionRole {
                return args.role
            }


        }
    }


    override fun wifiBootstrapRequest(): WifiDirectBootstrapRequest
    override fun bootstrapRequest(): BootstrapRequest


}