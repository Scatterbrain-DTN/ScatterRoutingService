package net.ballmerlabs.uscatterbrain.network.meshtastic

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.rx2.rxCompletable
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.db.Datastore
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.MeshtasticDao
import net.ballmerlabs.uscatterbrain.network.meshtastic.db.RoomDeviceStorage
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RadioTransport
import javax.inject.Named
import kotlin.coroutines.CoroutineContext

@MeshtasticConnectionScope
@Subcomponent(modules = [
    MeshtasticConnectionSubcomponent.MeshtasticConnectionModule::class
])
interface MeshtasticConnectionSubcomponent {

    object NamedSchedulers {
        const val BINDER_SCHEDULER = "meshtastic-binder"
        const val CALLBACK_SCHEDULER = "meshtastic-callbacks"
        const val COROUTINE_CONTEXT = "meshtastic-coroutine-context"
    }

    companion object {
        const val CONNECTION_NAME = "connection-name"
    }

    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        fun client(client: RadioTransport): Builder
        @BindsInstance
        fun name(@Named(CONNECTION_NAME) name: String): Builder
        fun build(): MeshtasticConnectionSubcomponent?
    }


    @Module(subcomponents = [
        MeshtasticSessionSubcomponent::class
    ])
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
            @Named(NamedSchedulers.COROUTINE_CONTEXT)
            fun providesCoroutineContext(): CoroutineScope {
                return CoroutineScope(Dispatchers.Default)
            }


            @Provides
            @MeshtasticConnectionScope
            fun providesRadioClient(transport: RadioTransport, storage: RoomDeviceStorage, @Named(
                NamedSchedulers.COROUTINE_CONTEXT) coroutineScope: CoroutineScope): RadioClient {
                return RadioClient.Builder()
                    .autoReconnect(false)
                    .storage(storage)
                    .coroutineContext(coroutineScope.coroutineContext)
                    .transport(transport)
                    .build()
            }


            @Provides
            @MeshtasticConnectionScope
            fun providesMeshtasticDao(datastore: Datastore): MeshtasticDao {
                return datastore.meshtasticDao()
            }

            @Provides
            @MeshtasticConnectionScope
            @Named(NamedSchedulers.CALLBACK_SCHEDULER)
            fun providesCallbackScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(ScatterbrainThreadFactory(NamedSchedulers.CALLBACK_SCHEDULER))
            }

            @Provides
            @MeshtasticConnectionScope
            fun providesConnectionFinalizer(
                @Named(NamedSchedulers.CALLBACK_SCHEDULER)
                scheduler: Scheduler,
                client: RadioTransport
            ): MeshtasticConnectionFinalizer {
                return object : MeshtasticConnectionFinalizer {
                    override fun onFinalize() {
                        rxCompletable { client.disconnect() }
                            .onErrorComplete()
                            .andThen(
                            Completable.fromAction {
                                scheduler.shutdown()
                            }.onErrorComplete()
                        ).subscribe()
                    }
                }
            }
        }
    }

    fun module(): MeshtasticRadioModule
}


interface MeshtasticConnectionFinalizer {
    fun onFinalize()
}