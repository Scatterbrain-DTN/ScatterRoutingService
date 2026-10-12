package net.ballmerlabs.uscatterbrain.network.meshtastic


import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import net.ballmerlabs.scatterbrainsdk.MeshtasticConnectionConfig
import net.ballmerlabs.scatterbrainsdk.MeshtasticMode
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticConnectionProviderImpl @Inject constructor(
    val firebaseCrashlytics: FirebaseWrapper,
    val builder: MeshtasticConnectionSubcomponent.Builder
) : MeshtasticConnectionProvider {


    private val log by scatterLog()
    private val connection = AtomicReference<Observable<MeshtasticConnectionSubcomponent>?>(null)
    private val connectionUpdate = BehaviorRelay.create<AtomicReference<Observable<MeshtasticConnectionSubcomponent>?>>()

    private fun connectBinder(config: MeshtasticConnectionConfig): Observable<MeshtasticConnectionSubcomponent> {
        return Observable.defer {
            val builder = when(config.mode) {
                MeshtasticMode.BLUETOOTH -> {
                    log.v("building BLUETOOTH meshtastic client")
                    builder.name("main")
                        .client(BleTransport(config.device!!.address))
                }
                MeshtasticMode.TCP -> {
                    log.v("building TCP meshtastic client")
                    builder.name("main")
                        .client(TcpTransport(host = config.host!!, port = config.port!!))
                }
            }
            val module = builder.build()!!

            Observable.just(module)
                .mergeWith(module.module().handlePackets())
        }
    }

    override fun awaitConnection(): Single<MeshtasticConnectionSubcomponent> {
        return connectionUpdate
            .flatMapMaybe { v ->
                when(val get = v.get()) {
                    null -> Maybe.empty()
                    else -> get.firstOrError()
                        .toMaybe()
                }
            }
            .firstOrError()
    }

    override fun connectBinderAsync(config: MeshtasticConnectionConfig) {
        connection.updateAndGet { v ->
            when(v) {
                null -> {
                    val obs = BehaviorSubject.create<MeshtasticConnectionSubcomponent>()
                    connectBinder(config).subscribe(obs)
                    obs
                }
                else -> v
            }
        }
    }

    override fun getConnection(): Maybe<MeshtasticConnectionSubcomponent> {
        return connectionUpdate
            .flatMapMaybe { v ->
                when(val get = v.get()) {
                    null -> Maybe.empty()
                    else -> get.firstOrError()
                        .toMaybe()
                }
            }
            .firstElement()
            .onErrorComplete()
    }

    init {
        connectionUpdate.accept(connection)
    }
}