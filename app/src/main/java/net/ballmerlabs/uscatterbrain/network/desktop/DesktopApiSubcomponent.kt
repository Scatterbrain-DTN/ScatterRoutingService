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
import javax.inject.Named
import javax.inject.Provider

@Subcomponent(modules = [DesktopApiSubcomponent.DesktopApiModule::class])
@DesktopApiScope
interface DesktopApiSubcomponent {

    object NamedSchedulers {
        const val API_SERVER_SCHEDULER = "apiserver"
    }

    data class ServiceConfig(
        val name: String
    )

    @Subcomponent.Builder
    @DesktopApiScope
    interface Builder {

        @BindsInstance
        fun serviceConfig(name: ServiceConfig): Builder

        @BindsInstance
        fun portSocket(portSocket: PortSocket): Builder

        fun build(): DesktopApiSubcomponent
    }

  @Module(subcomponents = [DesktopSessionSubcomponent::class])
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
              @Named(NamedSchedulers.API_SERVER_SCHEDULER) scheduler: Scheduler,
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
              return context.getSystemService(Context.NSD_SERVICE) as NsdManager
          }
      }
  }
    fun desktopServer(): DesktopApiServer
    fun finalizer(): DesktopFinalizer
}

interface DesktopFinalizer {
    fun onFinalize()
}