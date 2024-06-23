package net.ballmerlabs.uscatterbrain

import android.os.Bundle
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.net.InetAddress
import java.util.UUID
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
            fun providesFrom(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): UUID {
                return args.from
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(BootstrapRequestSubcomponent.PORT)
            fun providesPort(): Int {
                return 9999
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            fun providesRole(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): BluetoothLEModule.Role {
                return args.role
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(BootstrapRequestSubcomponent.OWNER_ADDRESS)
            fun providesGroupOwner(args: BootstrapRequestSubcomponent.WifiDirectBootstrapRequestArgs): InetAddress {
                return args.ownerAddress
            }


        }
    }


    override fun wifiBootstrapRequest(): WifiDirectBootstrapRequest
    override fun bootstrapRequest(): BootstrapRequest


}