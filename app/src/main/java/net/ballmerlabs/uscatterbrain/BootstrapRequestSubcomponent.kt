package net.ballmerlabs.uscatterbrain

import android.os.Bundle
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.net.InetAddress
import java.util.UUID
import javax.inject.Named


@Subcomponent(modules = [BootstrapRequestSubcomponent.BootstrapRequestDaggerModule::class])
@BootstrapRequestScope
interface BootstrapRequestSubcomponent {

    companion object {
        const val NAME = "bootstrapname"
        const val PASSPHRASE = "bootstrappassphrase"
        const val BAND = "band"
        const val PORT = "port"
        const val OWNER_ADDRESS = "owner-address"
    }

    data class WifiDirectBootstrapRequestArgs(
            val name: String,
            val passphrase: String,
            val role: BluetoothLEModule.Role,
            val band: Int,
            val port: Int,
            val ownerAddress: InetAddress,
            val from: UUID
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
            fun providesFrom(args: WifiDirectBootstrapRequestArgs): UUID {
                return args.from
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
            @Named(BAND)
            fun providesBand(args: WifiDirectBootstrapRequestArgs): Int {
                return args.band
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(PORT)
            fun providesPort(args: WifiDirectBootstrapRequestArgs): Int {
                return args.port
            }


            @Provides
            @JvmStatic
            @BootstrapRequestScope
            fun providesRole(args: WifiDirectBootstrapRequestArgs): BluetoothLEModule.Role {
                return args.role
            }

            @Provides
            @JvmStatic
            @BootstrapRequestScope
            @Named(OWNER_ADDRESS)
            fun providesOwnerAddress(args: WifiDirectBootstrapRequestArgs): InetAddress {
                return args.ownerAddress
            }


        }
    }


    fun wifiBootstrapRequest(): WifiDirectBootstrapRequest
    fun bootstrapRequest(): BootstrapRequest


}