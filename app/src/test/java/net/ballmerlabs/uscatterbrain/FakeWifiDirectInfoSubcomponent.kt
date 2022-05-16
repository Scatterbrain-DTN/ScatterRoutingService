

package net.ballmerlabs.uscatterbrain
import android.net.wifi.WpsInfo
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.MAC_ADDRESS
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.NETWORK_NAME
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.PASSPHRASE
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfigImpl
import javax.inject.Named


@Subcomponent(modules = [FakeWifiDirectInfoSubcomponent.FakeWifiDirectDaggerModule::class])
@WifiDirectInfoScope
interface FakeWifiDirectInfoSubcomponent: WifiDirectInfoSubcomponent {

    @Subcomponent.Builder
    interface Builder: WifiDirectInfoSubcomponent.Builder {
        @BindsInstance
        override fun fakeWifiP2pConfig(args: WifiDirectInfoSubcomponent.WifiP2pConfigArgs): Builder

        override fun build(): WifiDirectInfoSubcomponent?
    }

    @Module
    abstract class FakeWifiDirectDaggerModule {

        @Module
        companion object {
            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            fun providesWpsInfo(): WpsInfo {
                return WpsInfo()
            }

            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            @Named(PASSPHRASE)
            fun providesPassphrase(args: WifiDirectInfoSubcomponent.WifiP2pConfigArgs): String? {
                return args.passphrase
            }

            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            @Named(MAC_ADDRESS)
            fun providesDeviceAddress(args: WifiDirectInfoSubcomponent.WifiP2pConfigArgs): String? {
                return args.deviceAddress
            }
            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            @Named(NETWORK_NAME)
            fun providesNetworkName(args: WifiDirectInfoSubcomponent.WifiP2pConfigArgs): String? {
                return args.networkName
            }
        }
    }


    override fun fakeWifiP2pConfig(): FakeWifiP2pConfigImpl


}