package net.ballmerlabs.uscatterbrain.mock.meshtastic

import android.content.IntentFilter
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionSubcomponent.NamedSchedulers
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticRadioModule
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticRadioModuleImpl
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticSessionSubcomponent
import org.mockito.kotlin.mock
import javax.inject.Named

@MeshtasticConnectionScope
@Subcomponent(modules = [
    FakeMeshtasticConnectionSubcomponent.MeshtasticConnectionModule::class
])
interface FakeMeshtasticConnectionSubcomponent: MeshtasticConnectionSubcomponent {

    companion object {
        const val REMOTE_STATE = "remote-mesh-state"
        const val MY_ID = "my-id"
    }

    @Subcomponent.Builder
    interface Builder: MeshtasticConnectionSubcomponent.Builder {
        @BindsInstance
        fun myId(@Named(MY_ID) id: String): Builder

        override fun build(): FakeMeshtasticConnectionSubcomponent?
    }

    @Module(subcomponents = [ FakeMeshtasticSessionSubcomponent::class ])
    abstract class MeshtasticConnectionModule {
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

    fun sessionBuilder(): MeshtasticSessionSubcomponent.Builder
    fun radioModule(): MeshtasticRadioModule
}