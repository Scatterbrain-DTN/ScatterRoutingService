package net.ballmerlabs.uscatterbrain.network.meshtastic

import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.util.FirebaseWrapper
import net.ballmerlabs.uscatterbrain.util .scatterLog
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticConnectionImpl @Inject constructor(
    val context: Context,
    val receiver: MeshBroadcastReceiver,
    val intentFilter: IntentFilter,
    val crashlytics: FirebaseWrapper,
    val finalizer: MeshtasticConnectionFinalizer,
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.BINDER_SCHEDULER) val scheduler: Scheduler,
) : MeshtasticConnection {

    private val log by scatterLog()

    override fun subscribeReceiver() {
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
           //service.subscribeReceiver(context.packageName, "net.ballmerlabs.scatterroutingservice")
            log.v("meshtastic receiver subscribed")
        } catch (exc: Exception) {
            log.w("failed to subscribeReceiver: $exc")
            crashlytics.recordException(exc)
        }
    }

    override fun unsubscribeReceiver() {
        try {
            context.unregisterReceiver(receiver)
        } catch (exc: Exception) {
            log.w("failed to unsubscribeReceiver: $exc")
            crashlytics.recordException(exc)
        }
    }
    @Throws(Throwable::class)
    protected fun finalize() {
        finalizer.onFinalize()
    }
}