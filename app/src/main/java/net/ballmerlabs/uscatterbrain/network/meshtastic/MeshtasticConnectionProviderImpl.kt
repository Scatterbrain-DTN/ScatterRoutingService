package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.IMeshService
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticConnectionProviderImpl @Inject constructor(
    val binderProvider: MeshtasticBinderProvider,
    val firebaseCrashlytics: FirebaseWrapper,
    val builder: MeshtasticConnectionSubcomponent.Builder
) : MeshtasticConnectionProvider {


    private val log by scatterLog()
    private val connection = AtomicReference<Observable<MeshtasticConnectionSubcomponent>?>(null)
    private val connectionUpdate = BehaviorRelay.create<AtomicReference<Observable<MeshtasticConnectionSubcomponent>?>>()

    private fun connectBinder(): Observable<MeshtasticConnectionSubcomponent> {
        return  binderProvider.connectBinder().map { v ->
            builder.service(v).build()!!
        }
    }

    override fun awaitConnection(): Single<MeshtasticConnection> {
        return connectionUpdate
            .flatMapMaybe { v ->
                when(val get = v.get()) {
                    null -> Maybe.empty()
                    else -> get.firstOrError()
                        .map { c -> c.connection() }
                        .toMaybe()
                }
            }
            .firstOrError()
    }

    override fun connectBinderAsync() {
        connection.updateAndGet { v ->
            when(v) {
                null -> connectBinder()
                else -> v
            }
        }
    }

    override fun getConnection(): Maybe<MeshtasticConnection> {
        return connectionUpdate
            .flatMapMaybe { v ->
                when(val get = v.get()) {
                    null -> Maybe.empty()
                    else -> get.firstOrError()
                        .map { c -> c.connection() }
                        .toMaybe()
                }
            }
            .firstElement()
    }

    init {
        connectionUpdate.accept(connection)
    }
}