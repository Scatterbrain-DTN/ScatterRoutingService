package net.ballmerlabs.uscatterbrain

import android.net.wifi.WpsInfo
import dagger.*
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfig
import net.ballmerlabs.uscatterbrain.network.wifidirect.FakeWifiP2pConfigImpl
import javax.inject.Named


@Subcomponent(modules = [WifiDirectInfoSubcomponent.WifiDirectDaggerModule::class])
@WifiDirectInfoScope
interface WifiDirectInfoSubcomponent {

    data class WifiP2pConfigArgs(
            val passphrase: String? = "",
            val deviceAddress: String? = "02:00:00:00:00:00",
            val networkName: String? = ""
    )

    companion object {
        const val PASSPHRASE = "passphrase"
        const val MAC_ADDRESS = "mac_address"
        const val NETWORK_NAME = "network_name"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun fakeWifiP2pConfig(args: WifiP2pConfigArgs): Builder

        fun build(): WifiDirectInfoSubcomponent?
    }

    @Module
    abstract class WifiDirectDaggerModule {

        @Binds
        @WifiDirectInfoScope
        abstract fun bindsFakeConfig(config: FakeWifiP2pConfigImpl): FakeWifiP2pConfig

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
            fun providesPassphrase(args: WifiP2pConfigArgs): String? {
                return args.passphrase
            }

            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            @Named(MAC_ADDRESS)
            fun providesDeviceAddress(args: WifiP2pConfigArgs): String? {
                return args.deviceAddress
            }
            @Provides
            @JvmStatic
            @WifiDirectInfoScope
            @Named(NETWORK_NAME)
            fun providesNetworkName(args: WifiP2pConfigArgs): String? {
                return args.networkName
            }
        }
    }


    fun fakeWifiP2pConfig(): FakeWifiP2pConfig


}