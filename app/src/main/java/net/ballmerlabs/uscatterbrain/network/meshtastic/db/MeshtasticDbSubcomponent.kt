package net.ballmerlabs.uscatterbrain.network.meshtastic.db

import dagger.Module
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.MeshtasticDbScope

@MeshtasticDbScope
@Subcomponent(modules = [MeshtasticDbSubcomponent.MeshtasticDbModule::class])
interface MeshtasticDbSubcomponent {

    @Subcomponent.Builder
    interface Builder {
        fun build(): MeshtasticDbSubcomponent?
    }


    @Module
    abstract class MeshtasticDbModule {
    }


    fun deviceStorage(): RoomDeviceStorage
}