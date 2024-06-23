package net.ballmerlabs.uscatterbrain.network.desktop
import android.content.Context
import android.net.nsd.NsdManager
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.wifidirect.PortSocket
import org.mockito.kotlin.mock
import javax.inject.Named
import javax.inject.Provider

@Subcomponent(modules = [FakeDesktopApiSubcomponent.DesktopApiModule::class])
@DesktopApiScope
interface FakeDesktopApiSubcomponent: DesktopApiSubcomponent {

    object NamedSchedulers {
        const val API_SERVER_SCHEDULER = "apiserver"
    }

    object NamedPrefs {
        const val ENCRYPTED_PREFS = "encrypted-prefs"
    }

    object NamedStrings {
        const val SERVICE_NAME = "service-name"
    }

    @Subcomponent.Builder
    interface Builder: DesktopApiSubcomponent.Builder {

        @BindsInstance
        override fun serviceConfig(name: DesktopApiSubcomponent.ServiceConfig): Builder

        @BindsInstance
        override fun portSocket(portSocket: PortSocket): Builder

        override fun build(): DesktopApiSubcomponent
    }

    @Module(subcomponents = [FakeDesktopSessionSubcomponent::class])
    abstract class DesktopApiModule {

        @Binds
        @DesktopApiScope
        abstract fun bindsDesktopApiServer(desktopApiServerImpl: DesktopApiServerImpl): DesktopApiServer

        @Binds
        @DesktopApiScope
        abstract fun bindsState(state: DesktopKeyManagerImpl): DesktopKeyManager

        @Binds
        @DesktopApiScope
        abstract fun bindsNsdAdvertiser(state: NsdAdvertiserImpl): NsdAdvertiser

        @Binds
        @DesktopApiScope
        abstract fun bindsSessionBuilder(builder: FakeDesktopSessionSubcomponent.Builder): DesktopSessionSubcomponent.Builder

        @Module
        companion object {

            @Provides
            @DesktopApiScope
            @Named(NamedSchedulers.API_SERVER_SCHEDULER)
            fun providesApiServerScheduler(): Scheduler {
                return RxJavaPlugins.createIoScheduler(ScatterbrainThreadFactory(
                    NamedSchedulers.API_SERVER_SCHEDULER
                ))
            }


            @Provides
            @DesktopApiScope
            fun providesDesktopApiFinalizer(
                @Named(DesktopApiSubcomponent.NamedSchedulers.API_SERVER_SCHEDULER) scheduler: Scheduler,
                serverSocket: PortSocket,
                desktopApiServer: Provider<DesktopApiServer>
            ): DesktopFinalizer {
                return object : DesktopFinalizer {
                    override fun onFinalize() {
                        scheduler.shutdown()
                        serverSocket.close()
                        desktopApiServer.get().shutdown()
                    }

                }
            }

            @Provides
            @DesktopApiScope
            fun providesNsdService(
                context: Context
            ): NsdManager {
                return mock {  }
            }

        }
    }
}