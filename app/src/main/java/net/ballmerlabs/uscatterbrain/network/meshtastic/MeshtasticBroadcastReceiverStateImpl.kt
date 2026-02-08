package net.ballmerlabs.uscatterbrain.network.meshtastic


import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeInfo
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

    private val messageStatus = PublishProcessor.create<MessageStatusEvent>()
    private val connectionState = PublishProcessor.create<String>()
    private val nodeChange = PublishProcessor.create<NodeInfo>()
    private val dataPacket = PublishProcessor.create<DataPacket>()
    override fun acceptMessageStatus(messageStatus: MessageStatusEvent) {
        log.v("acceptMessageStatus $messageStatus")
        this.messageStatus.onNext(messageStatus)
    }

    override fun onMessageStatus(): Flowable<MessageStatusEvent> {
        return messageStatus.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptConnectionState(connectionState: String) {
        this.connectionState.onNext(connectionState)
    }

    override fun onConnectionState(): Flowable<String> {
        return connectionState.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptNodeChange(nodeInfo: NodeInfo) {
        log.v("acceptNodeChange $nodeInfo")
        this.nodeChange.onNext(nodeInfo)
    }

    override fun onNodeChange(): Flowable<NodeInfo> {
        return nodeChange.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptDataPacket(dataPacket: DataPacket) {
        log.v("acceptDataPacket $dataPacket")
        this.dataPacket.onNext(dataPacket)
    }

    override fun onDataPacket(): Flowable<DataPacket> {
        return dataPacket.delay(0, TimeUnit.SECONDS, callbacks)
    }

    init {
        log.e("MeshtasticBroadcastReceiverState init")
    }
}