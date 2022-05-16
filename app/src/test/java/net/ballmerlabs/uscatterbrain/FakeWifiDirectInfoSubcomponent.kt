

package net.ballmerlabs.uscatterbrain
import android.net.wifi.WpsInfo
import dagger.*
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.MAC_ADDRESS
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.NETWORK_NAME
import net.ballmerlabs.uscatterbrain.WifiDirectInfoSubcomponent.Companion.PASSPHRASE
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.MockFakeWifiP2pConfigImpl
import org.mockito.kotlin.mock
import javax.inject.Named
import javax.inject.Singleton


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

        @Binds
        @WifiDirectInfoScope
        abstract fun bindsFakeConfig(config: MockFakeWifiP2pConfigImpl): FakeWifiP2pConfig

        @Module
        companion object {
            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            fun providesWpsInfo(): WpsInfo {
                return mock {  }
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


    override fun fakeWifiP2pConfig(): FakeWifiP2pConfig


}