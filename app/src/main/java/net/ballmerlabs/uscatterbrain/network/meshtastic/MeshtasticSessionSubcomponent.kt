package net.ballmerlabs.uscatterbrain.network.meshtastic

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import java.util.Date
import javax.inject.Named

@MeshtasticSessionScope
@Subcomponent(modules = [ MeshtasticSessionSubcomponent.MeshtasticSessionModule::class ])
interface MeshtasticSessionSubcomponent {

    companion object {
        const val ROUTER_ID = "router-id"
        const val PARSE_SCHEDULER = "meshtastic-parse"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        @Named(ROUTER_ID)
        fun id(id: String): Builder
        fun build(): MeshtasticSessionSubcomponent
    }


    @Module
    abstract class MeshtasticSessionModule {
        @Binds
        abstract fun bindsSessionState(meshtasticSessionState: MeshtasticSessionStateImpl): MeshtasticSessionState

        companion object {
            @Provides
            @MeshtasticSessionScope
            fun providesCreationDate(): Date {
                return Date()
            }

            @Provides
            @MeshtasticSessionScope
            @Named(PARSE_SCHEDULER)
            fun providesParseScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(PARSE_SCHEDULER))
            }
        }
    }


    fun state(): MeshtasticSessionState

    @Named(ROUTER_ID)
    fun routerId(): String

    fun creationDate(): Date
}