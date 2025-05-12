package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.IntentFilter
import com.geeksville.mesh.IMeshService
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import javax.inject.Named

@MeshtasticConnectionScope
@Subcomponent(modules = [
    MeshtasticConnectionSubcomponent.MeshtasticConnectionModule::class
])
interface MeshtasticConnectionSubcomponent {

    object NamedSchedulers {
        const val BINDER_SCHEDULER = "meshtastic-binder"
        const val CALLBACK_SCHEDULER = "meshtastic-callbacks"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun service(service: IMeshService): Builder

        fun build(): MeshtasticConnectionSubcomponent?
    }


    @Module(subcomponents = [ MeshtasticSessionSubcomponent::class ])
    abstract class MeshtasticConnectionModule {
        @Binds
        @MeshtasticConnectionScope
        abstract fun bindsMeshtasticConnection(impl: MeshtasticConnectionImpl): MeshtasticConnection

        @Binds
        abstract fun bindsMeshBroadcastReceiver(impl: MeshBroadcastReceiverImpl): MeshBroadcastReceiver

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
                return IntentFilter().apply {
                    addAction(ACTION_MESH_CONNECTED)
                    addAction(ACTION_MESSAGE_STATUS)
                    addAction(ACTION_NODE_CHANGE)
                    addAction(MeshBroadcastReceiver.actionReceived(PORT_NUMBER))
                }
            }

            @Provides
            @MeshtasticConnectionScope
            @Named(NamedSchedulers.CALLBACK_SCHEDULER)
            fun providesCallbackScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.CALLBACK_SCHEDULER))
            }
        }
    }

    fun connection(): MeshtasticConnection
}