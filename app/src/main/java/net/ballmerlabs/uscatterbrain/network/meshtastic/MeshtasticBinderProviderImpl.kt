package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AtomicReference
import com.geeksville.mesh.IMeshService
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticBinderProviderImpl @Inject constructor(
    val context: Context,
    val firebaseCrashlytics: FirebaseWrapper
) : MeshtasticBinderProvider {
    private val log by scatterLog()
    private val connection = BehaviorRelay.create<Maybe<IMeshService>>()
    private val disp = AtomicReference<Disposable?>(null)
    private fun connectBinder(): Observable<IMeshService> {
        return Observable.create { obs ->
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    obs.onNext(IMeshService.Stub.asInterface(service))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    obs.onComplete()
                }

                override fun onBindingDied(name: ComponentName?) {
                    obs.onError(IllegalStateException("binding died"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    obs.onError(IllegalStateException("binding null"))
                }
            }
            val intent = Intent(BinderWrapper.BIND_ACTION).apply {
                setClassName(
                    "com.geeksville.mesh",
                    "com.geeksville.mesh.service.MeshService"
                )
            }
            context.bindService(intent, callback, Context.BIND_AUTO_CREATE)
        }
            .doFinally {
                connection.accept(Maybe.empty())
            }
            .doOnNext { v ->
                connection.accept(Maybe.just(v))
            }
    }


    override fun awaitConnection(): Single<IMeshService> {
        return connection.flatMapMaybe { v -> v }.firstOrError()
    }

    override fun connectBinderAsync() {
        val d = connectBinder().subscribe(
            {  log.v("connected meshtastic binder") },
            { err ->
                log.e("failed to connect meshtastic binder: $err")
                firebaseCrashlytics.recordException(err)
            }
        )

        disp.getAndSet(d)?.dispose()
    }

    override fun getConnection(): Maybe<IMeshService> {
        return connection.flatMapMaybe { v -> v }.firstElement()
    }

    init {
        connection.accept(Maybe.empty())
    }
}