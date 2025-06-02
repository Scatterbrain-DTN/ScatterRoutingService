package net.ballmerlabs.uscatterbrain.mock.meshtastic

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionStateImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Builder
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Companion.PARSE_SCHEDULER
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Companion.ROUTER_ID
import java.util.Date
import javax.inject.Named

@MeshtasticSessionScope
@Subcomponent(modules = [ FakeMeshtasticSessionSubcomponent.FakeMeshtasticSessionModule::class ])
interface FakeMeshtasticSessionSubcomponent : MeshtasticSessionSubcomponent {

    @Subcomponent.Builder
    interface Builder: MeshtasticSessionSubcomponent.Builder {

        @BindsInstance
        override fun id(@Named(ROUTER_ID) id: String): MeshtasticSessionSubcomponent.Builder

        override fun build(): MeshtasticSessionSubcomponent
    }

    @Module
    abstract class FakeMeshtasticSessionModule {

        @Binds
        @MeshtasticSessionScope
        abstract fun bindsSessionState(sessionStateImpl: MeshtasticSessionStateImpl): MeshtasticSessionState

        companion object {
            @Provides
            @MeshtasticSessionScope
            @Named(PARSE_SCHEDULER)
            fun providesParseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(PARSE_SCHEDULER))
            }

            @Provides
            @MeshtasticSessionScope
            fun providesCreationDate(): Date {
                return Date()
            }

        }
    }
}