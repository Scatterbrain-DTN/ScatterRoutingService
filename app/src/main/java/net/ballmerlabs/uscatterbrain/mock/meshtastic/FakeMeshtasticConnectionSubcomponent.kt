package net.ballmerlabs.uscatterbrain.mock.meshtastic

import android.content.IntentFilter
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
import net.ballmerlabs.uscatterbrain.network.meshtastic.ACTION_MESH_CONNECTED
import net.ballmerlabs.uscatterbrain.network.meshtastic.ACTION_MESSAGE_STATUS
import net.ballmerlabs.uscatterbrain.network.meshtastic.ACTION_NODE_CHANGE
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiver
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshBroadcastReceiverImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverStateImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent.NamedSchedulers
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticRadioModule
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticRadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.PORT_NUMBER
import org.mockito.kotlin.mock
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

    @Module(subcomponents = [ FakeMeshtasticSessionSubcomponent::class ])
    abstract class MeshtasticConnectionModule {
        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshtasticConnection(impl: MeshtasticConnectionImpl): MeshtasticConnection

        @Binds
        abstract fun bindsBroadcastReceiver(impl: MeshBroadcastReceiverImpl): MeshBroadcastReceiver


        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshBroadcastReceiverState(impl: MeshtasticBroadcastReceiverStateImpl): MeshtasticBroadcastReceiverState

        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshtasticRadioModule(impl: MeshtasticRadioModuleImpl): MeshtasticRadioModule

        companion object {
            @Provides
            @MeshtasticConnectionScope
            @Named(NamedSchedulers.BINDER_SCHEDULER)
            fun providesBinderScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.BINDER_SCHEDULER))
            }

            @Provides
            @MeshtasticConnectionScope
            fun providesIntentFilter(): IntentFilter {
                return mock {  }
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