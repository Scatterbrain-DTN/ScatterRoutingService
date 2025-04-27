package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.geeksville.mesh.IMeshService
import io.reactivex.Observable
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshtasticBinderProviderImpl @Inject constructor(
    val context: Context
) : MeshtasticBinderProvider {
    override fun connectBinder(): Observable<IMeshService> {
        return Observable.create { obs ->
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
    }
}