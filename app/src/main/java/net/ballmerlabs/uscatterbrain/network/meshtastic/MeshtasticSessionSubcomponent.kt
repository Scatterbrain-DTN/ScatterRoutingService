package net.ballmerlabs.uscatterbrain.network.meshtastic

import dagger.BindsInstance
import dagger.Module
import dagger.Subcomponent
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

    }
}