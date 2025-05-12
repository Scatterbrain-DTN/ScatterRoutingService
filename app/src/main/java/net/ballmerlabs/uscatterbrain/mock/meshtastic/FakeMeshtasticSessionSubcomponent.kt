package net.ballmerlabs.uscatterbrain.mock.meshtastic

import dagger.BindsInstance
import dagger.Module
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent.Builder
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

    }
}