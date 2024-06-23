package net.ballmerlabs.uscatterbrain.network.desktop

import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopSessionSubcomponent.NamedKeys
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import javax.inject.Named

@Subcomponent(modules = [FakeDesktopSessionSubcomponent.FakeDesktopSessionModule::class])
@DesktopSessionScope
interface FakeDesktopSessionSubcomponent : DesktopSessionSubcomponent {

    @Subcomponent.Builder
    @DesktopSessionScope
    interface Builder: DesktopSessionSubcomponent.Builder  {
        override fun build(): DesktopSessionSubcomponent
    }

    @Module
    abstract class FakeDesktopSessionModule {
        @Module
        companion object {
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