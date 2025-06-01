package net.ballmerlabs.uscatterbrain.mock.meshtastic

import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Builder
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Companion.PARSE_SCHEDULER
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Companion.ROUTER_ID
import javax.inject.Named

@MeshtasticSessionScope
@Subcomponent(modules = [ FakeMeshtasticSessionSubcomponent.FakeMeshtasticSessionModule::class ])
interface FakeMeshtasticSessionSubcomponent : MeshtasticSessionSubcomponent {

    @Subcomponent.Builder
    interface Builder: MeshtasticSessionSubcomponent.Builder {
        @BindsInstance
        @Named(ROUTER_ID)
        override fun id(id: String): MeshtasticSessionSubcomponent.Builder
        override fun build(): MeshtasticSessionSubcomponent
    }

    @Module
    abstract class FakeMeshtasticSessionModule {

        companion object {
            @Provides
            @MeshtasticSessionScope
            @Named(PARSE_SCHEDULER)
            fun providesParseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(PARSE_SCHEDULER))
            }
        }
    }
}