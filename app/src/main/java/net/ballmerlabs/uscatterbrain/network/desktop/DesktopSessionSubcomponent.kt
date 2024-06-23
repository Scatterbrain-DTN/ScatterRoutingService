package net.ballmerlabs.uscatterbrain.network.desktop

import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import dagger.internal.Beta
import net.ballmerlabs.uscatterbrain.network.desktop.entity.DesktopClient
import net.ballmerlabs.uscatterbrain.scheduler.DesktopSession
import java.net.Socket
import javax.inject.Named

data class DesktopSessionConfig(
    val tx: ByteArray,
    val rx: ByteArray,
    val fingerprint: ByteArray,
    val remotepub: ByteArray,
    val db: DesktopClient,
    val kx: PublicKeyPair
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DesktopSessionConfig

        if (!tx.contentEquals(other.tx)) return false
        if (!rx.contentEquals(other.rx)) return false
        if (!fingerprint.contentEquals(other.fingerprint)) return false
        if (!remotepub.contentEquals(other.remotepub)) return false
        if (db != other.db) return false
        if (kx != other.kx) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tx.contentHashCode()
        result = 31 * result + rx.contentHashCode()
        result = 31 * result + fingerprint.contentHashCode()
        result = 31 * result + remotepub.contentHashCode()
        result = 31 * result + db.hashCode()
        result = 31 * result + kx.hashCode()
        return result
    }
}

@DesktopSessionScope
@Subcomponent(modules = [DesktopSessionSubcomponent.DesktopSessionModule::class])
interface DesktopSessionSubcomponent {

    object NamedKeys {
        const val FINGERPRINT = "fingerprint"
        const val REMOTEPUB = "remotepub"
        const val TX = "tx"
        const val RX = "rx"
    }

    @Subcomponent.Builder
    @DesktopSessionScope
    interface Builder {
        @BindsInstance
        fun config(config: DesktopSessionConfig): Builder

        @BindsInstance
        fun socket(socket: Socket): Builder

        @BindsInstance
        fun stateEntry(stateEntry: StateEntry): Builder

        fun build(): DesktopSessionSubcomponent
    }

    @Module
    abstract class DesktopSessionModule {


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


    fun session(): DesktopSession
}