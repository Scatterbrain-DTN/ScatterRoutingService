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
    private val connection = BehaviorRelay.create<Maybe<MeshtasticConnectionSubcomponent>>()
    private val disp = AtomicReference<Disposable?>(null)

    private fun connectBinder(): Observable<MeshtasticConnectionSubcomponent> {
        return binderProvider.connectBinder().map { v ->
            builder.service(v).build()!!
        }
    }

    override fun awaitConnection(): Single<MeshtasticConnection> {
        return connection
            .flatMapMaybe { v -> v }
            .map { v -> v.connection() }
            .firstOrError()
    }

    override fun connectBinderAsync() {
        val d = connectBinder().subscribe(
            { log.v("connected meshtastic binder") },
            { err ->
                log.e("failed to connect meshtastic binder: $err")
                firebaseCrashlytics.recordException(err)
            }
        )

        disp.getAndSet(d)?.dispose()
    }

    override fun getConnection(): Maybe<MeshtasticConnection> {
        return connection
            .flatMapMaybe { v -> v }
            .map { v -> v.connection() }
            .firstElement()
    }

    init {
        connection.accept(Maybe.empty())
    }
}