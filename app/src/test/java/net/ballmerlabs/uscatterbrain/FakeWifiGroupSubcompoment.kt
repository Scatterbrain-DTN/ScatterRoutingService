package net.ballmerlabs.uscatterbrain

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import javax.inject.Named

@Subcomponent(modules = [
    FakeWifiGroupSubcompoment.FakeWifiDirectSessionModule::class
])
@WifiGroupScope
interface FakeWifiGroupSubcompoment: WifiGroupSubcomponent {
    @Subcomponent.Builder
    interface Builder : WifiGroupSubcomponent.Builder {
        @BindsInstance
        override fun bootstrapRequest(wifiDirectBootstrapRequest: WifiDirectBootstrapRequest): Builder

        override fun build(): FakeWifiGroupSubcompoment
    }

    @Module
    abstract class FakeWifiDirectSessionModule {


        @Module
        companion object {
            @Provides
            @JvmStatic
            @WifiGroupScope
            @Named(WifiGroupSubcomponent.NamedSchedulers.WIFI_OPERATIONS)
            fun providesWifiOperationsScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory("wifi-ops"))
            }

            @Provides
            @JvmStatic
            @WifiGroupScope
            fun providesGroupFinalizer(
                @Named(WifiGroupSubcomponent.NamedSchedulers.WIFI_OPERATIONS) wifiOpsScheduler: Scheduler
            ): GroupFinalizer {
                return object : GroupFinalizer {
                    override fun onFinalize() {
                        wifiOpsScheduler.shutdown()
                    }
                }
            }
        }

    }
}