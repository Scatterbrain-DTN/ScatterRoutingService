package net.ballmerlabs.uscatterbrain.network.meshtastic

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import java.util.Date
import javax.inject.Named

@MeshtasticSessionScope
@Subcomponent(modules = [ MeshtasticSessionSubcomponent.MeshtasticSessionModule::class ])
interface MeshtasticSessionSubcomponent {

    companion object {
        const val ROUTER_ID = "router-id"
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
        }
    }


    fun state(): MeshtasticSessionState

    @Named(ROUTER_ID)
    fun routerId(): String

    fun creationDate(): Date
}