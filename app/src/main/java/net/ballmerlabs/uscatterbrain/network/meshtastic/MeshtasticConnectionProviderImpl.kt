package net.ballmerlabs.uscatterbrain.network.meshtastic


import com.jakewharton.rxrelay2.BehaviorRelay
import io.ktor.http.Url
import io.reactivex.Maybe
import io.reactivex.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ballmerlabs.scatterbrainsdk.MeshtasticConnectionConfig
import net.ballmerlabs.scatterbrainsdk.MeshtasticMode
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import org.meshtastic.sdk.transport.ble.BleTransport
import org.meshtastic.sdk.transport.tcp.TcpTransport
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class MeshtasticConnectionProviderImpl @Inject constructor(
    val firebaseCrashlytics: FirebaseWrapper,
    val builder: MeshtasticConnectionSubcomponent.Builder,
    @Named(RoutingServiceComponent.NamedSchedulers.GLOBAL_COROUTINE) val scope: CoroutineScope,
) : MeshtasticConnectionProvider {


    private val log by scatterLog()
    private val connection = AtomicReference<MeshtasticConnectionSubcomponent?>(null)
    private val connectionUpdate =
        BehaviorRelay.create<Boolean>()


    private fun acceptConnection(conn: MeshtasticConnectionSubcomponent?) {
        connection.set(conn)
        connectionUpdate.accept(conn != null)
    }

    private fun connectBinder(config: MeshtasticConnectionConfig, retry: Int = 5) {
        scope.launch {
            for (x in 0..retry) {
                try {
                    val builder = when (config.mode) {
                        MeshtasticMode.BLUETOOTH -> {
                            log.v("building BLUETOOTH meshtastic client")
                            builder.name("main")
                                .client(BleTransport(config.connection))
                        }

                        MeshtasticMode.TCP -> {
                            log.v("building TCP meshtastic client")
                            val url = Url(config.connection)
                            builder.name("main")
                                .client(TcpTransport(host = url.host, port = url.port))
                        }
                    }
                    val module = builder.build()!!
                    module.module().connect()
                    acceptConnection(module)
                    module.module().handlePackets()
                } catch (exc: Exception) {
                    log.e("EXCEPTION failed to connect $exc")
                    acceptConnection(null)
                }
            }
        }
    }

    override fun awaitConnection(): Single<MeshtasticConnectionSubcomponent> {
        return connectionUpdate
            .filter { v -> v }
            .firstOrError()
            .map { v -> connection.get()!! }
            .timeout(45, TimeUnit.SECONDS)
    }

    override fun connectBinderAsync(config: MeshtasticConnectionConfig) {
        connectBinder(config)
    }

    override fun getConnection(): Maybe<MeshtasticConnectionSubcomponent> {
        return connectionUpdate
            .firstOrError()
            .flatMapMaybe { v ->
                when (v) {
                    true -> Maybe.just(connection.get()!!)
                    false -> Maybe.empty()
                }
            }

    }

    init {
        connectionUpdate.accept(false)
    }
}