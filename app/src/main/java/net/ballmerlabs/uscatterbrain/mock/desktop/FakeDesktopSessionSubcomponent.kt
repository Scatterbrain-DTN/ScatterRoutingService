package net.ballmerlabs.uscatterbrain.mock.desktop

import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import io.reactivex.Scheduler
import io.reactivex.plugins.RxJavaPlugins
import net.ballmerlabs.uscatterbrain.ScatterbrainThreadFactory
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionConfig
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionScope
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionSubcomponent
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionSubcomponent.NamedKeys
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionSubcomponent.NamedSchedulers
import net.ballmerlabs.uscatterbrain.network.desktop.PublicKeyPair
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import javax.inject.Named

@Subcomponent(modules = [FakeDesktopSessionSubcomponent.FakeDesktopSessionModule::class])
@DesktopSessionScope
interface FakeDesktopSessionSubcomponent : DesktopSessionSubcomponent {

    @Subcomponent.Builder
    @DesktopSessionScope
    interface Builder: DesktopSessionSubcomponent.Builder {
        override fun build(): DesktopSessionSubcomponent
    }

    @Module
    abstract class FakeDesktopSessionModule {
        @Module
        companion object {

            @Provides
            @DesktopSessionScope
            @Named(NamedSchedulers.API_SESSION_WRITE_SCHED)
            fun providesApiSessionWriteScheduler(): Scheduler {
                return RxJavaPlugins.createSingleScheduler(
                    ScatterbrainThreadFactory(
                        NamedSchedulers.API_SESSION_WRITE_SCHED
                    )
                )
            }

            @Provides
            @DesktopSessionScope
            @Named(NamedKeys.TX)
            fun providesTx(config: DesktopSessionConfig): ByteArray {
                return config.tx
            }

            @Provides
            @DesktopSessionScope
            @Named(NamedKeys.RX)
            fun providesRx(config: DesktopSessionConfig): ByteArray {
                return config.rx
            }

            @Provides
            @DesktopSessionScope
            @Named(NamedKeys.REMOTEPUB)
            fun providesRemotePub(config: DesktopSessionConfig): ByteArray {
                return config.remotepub
            }

            @Provides
            @DesktopSessionScope
            @Named(NamedKeys.FINGERPRINT)
            fun providesFingerpriunt(config: DesktopSessionConfig): ByteArray {
                return config.fingerprint
            }

            @Provides
            @DesktopSessionScope
            fun provideKx(config: DesktopSessionConfig): PublicKeyPair {
                return config.kx
            }

            @Provides
            @DesktopSessionScope
            fun providesDesktopClient(config: DesktopSessionConfig): DesktopClient {
                return config.db
            }
        }
    }
}