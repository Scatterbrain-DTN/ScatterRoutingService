package net.ballmerlabs.uscatterbrain.mock.meshtastic

import com.geeksville.mesh.IMeshService
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnection
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiverImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverStateImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent.NamedSchedulers
import javax.inject.Named

@MeshtasticConnectionScope
@Subcomponent(modules = [
    FakeMeshtasticConnectionSubcomponent.MeshtasticConnectionModule::class
])
interface FakeMeshtasticConnectionSubcomponent: MeshtasticConnectionSubcomponent {

    @Subcomponent.Builder
    interface Builder: MeshtasticConnectionSubcomponent.Builder {
        @BindsInstance
        override fun service(service: IMeshService): Builder

        override fun build(): MeshtasticConnectionSubcomponent?
    }

    @Module
    abstract class MeshtasticConnectionModule {
        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshtasticConnection(impl: MeshtasticConnectionImpl): MeshtasticConnection

        @Binds
        abstract fun bindsBroadcastReceiver(impl: MeshBroadcastReceiverImpl): MeshBroadcastReceiver


        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshBroadcastReceiverState(impl: MeshtasticBroadcastReceiverStateImpl): MeshtasticBroadcastReceiverState

        companion object {
            @Provides
            @MeshtasticConnectionScope
            @Named(NamedSchedulers.BINDER_SCHEDULER)
            fun providesBinderScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BINDER_SCHEDULER))
            }

            @Provides
            @MeshtasticConnectionScope
            @Named(NamedSchedulers.CALLBACK_SCHEDULER)
            fun providesCallbackScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.CALLBACK_SCHEDULER))
            }
        }
    }
}