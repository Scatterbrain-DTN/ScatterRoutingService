package net.ballmerlabs.uscatterbrain

import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.network.wifidirect.GroupHandle
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiSessionConfig
import javax.inject.Named

@Subcomponent(modules = [WifiGroupSubcomponent.WifiGroupModule::class])
@WifiGroupScope
interface WifiGroupSubcomponent {

    object NamedSchedulers {
        const val WIFI_OPERATIONS = "wifi-ops"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun bootstrapRequest(wifiDirectBootstrapRequest: WifiDirectBootstrapRequest): Builder

        @BindsInstance
        fun info(info: WifiSessionConfig): Builder

        @BindsInstance
        fun serverSocket(serverSocket: PortSocket): Builder

        fun build(): WifiGroupSubcomponent
    }

    @Module
    abstract class WifiGroupModule {
        @Module
        companion object {

            @Provides
            @JvmStatic
            @WifiGroupScope
            @Named(NamedSchedulers.WIFI_OPERATIONS)
            fun providesWifiOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("wifi-ops"))
            }

            @Provides
            @JvmStatic
            @WifiGroupScope
            fun providesGroupFinalizer(
                @Named(NamedSchedulers.WIFI_OPERATIONS) wifiOpsScheduler: Scheduler,
                serverSocket: PortSocket,
            ): GroupFinalizer {
                return object : GroupFinalizer {
                    override fun onFinalize() {
                        wifiOpsScheduler.shutdown()
                        serverSocket.close()
                    }
                }
            }
        }
    }

    fun request(): WifiDirectBootstrapRequest
    fun groupHandle(): GroupHandle
}

interface GroupFinalizer {
    fun onFinalize()
}