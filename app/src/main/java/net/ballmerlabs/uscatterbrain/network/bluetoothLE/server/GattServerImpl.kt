package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.GattServerConnectionSubcomponent
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class GattServerImpl @Inject constructor(
        private val connectionBuilder: Provider<GattServerConnectionSubcomponent.Builder>,
        @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_IO) private val timeoutScheduler: Scheduler
): GattServer {
    override fun openServer(config: ServerConfig): Single<GattServerConnectionSubcomponent> {
        return Single.fromCallable {
            connectionBuilder.get()
                    .timeoutConfiguration(
                            TimeoutConfiguration(
                                    10,
                                    TimeUnit.SECONDS,
                                    timeoutScheduler
                            )
                    )
                    .build()

        }
            .flatMap { conn -> conn.connection().initializeServer(config).toSingleDefault(conn) }
    }
}