package net.ballmerlabs.uscatterbrain.network.bluetoothLE.server

import android.bluetooth.BluetoothGattServer
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
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
        @Named(RoutingServiceComponent.NamedSchedulers.IO) private val timeoutScheduler: Scheduler,
        @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) private val computeScheduler: Scheduler,
): GattServer {
    override fun openServer(config: ServerConfig): Observable<GattServerConnection> {
        return Observable.fromCallable {
            connectionBuilder.get()
                    .timeoutConfiguration(
                            TimeoutConfiguration(
                                    10,
                                    TimeUnit.SECONDS,
                                    timeoutScheduler
                            )
                    )
                    .build()
                    .connection()

        }
            .subscribeOn(computeScheduler)
            .observeOn(AndroidSchedulers.mainThread())
            .flatMapSingle { conn -> conn.initializeServer(config).toSingleDefault(conn) }
    }
}