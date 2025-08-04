package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.geeksville.mesh.IMeshService
import io.reactivex.Observable
import io.reactivex.Scheduler

import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class MeshtasticBinderProviderImpl @Inject constructor(
    val context: Context,
    val firebaseCrashlytics: FirebaseWrapper,
   @Named(RoutingServiceComponent.NamedSchedulers.MAIN_THREAD) val mainScheduler: Scheduler,
    @Named(RoutingServiceComponent.NamedSchedulers.COMPUTATION) val computation: Scheduler
) : MeshtasticBinderProvider {
    private val log by scatterLog()
    override fun connectBinder(): Observable<IMeshService> {
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
        }.doOnError { err ->
            log.e("error in meshtasticc binder: $err")
            firebaseCrashlytics.recordException(err)
        }
    }
}