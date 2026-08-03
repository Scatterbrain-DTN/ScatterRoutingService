package net.ballmerlabs.uscatterbrain.network.meshtastic


import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.processors.PublishProcessor
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticBroadcastReceiverStateImpl @Inject constructor(
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.CALLBACK_SCHEDULER) val callbacks: Scheduler,
) : MeshtasticBroadcastReceiverState {
    private val log by scatterLog()

    private val connectionState = PublishProcessor.create<String>()
    override fun acceptConnectionState(connectionState: String) {
        this.connectionState.onNext(connectionState)
    }

    override fun onConnectionState(): Flowable<String> {
        return connectionState.delay(0, TimeUnit.SECONDS, callbacks)
    }

    init {
        log.e("MeshtasticBroadcastReceiverState init")
    }
}