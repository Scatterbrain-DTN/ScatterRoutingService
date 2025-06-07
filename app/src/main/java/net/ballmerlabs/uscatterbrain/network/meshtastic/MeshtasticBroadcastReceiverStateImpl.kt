package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.NodeInfo
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import io.reactivex.Scheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

@MeshtasticConnectionScope
class MeshtasticBroadcastReceiverStateImpl @Inject constructor(
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.CALLBACK_SCHEDULER) val callbacks: Scheduler,
) : MeshtasticBroadcastReceiverState {
    private val log by scatterLog()

    private val messageStatus = PublishRelay.create<MessageStatusEvent>()
    private val connectionState = PublishRelay.create<String>()
    private val nodeChange = PublishRelay.create<NodeInfo>()
    private val dataPacket = PublishRelay.create<DataPacket>()
    override fun acceptMessageStatus(messageStatus: MessageStatusEvent) {
        log.v("acceptMessageStatus $messageStatus")
        this.messageStatus.accept(messageStatus)
    }

    override fun onMessageStatus(): Observable<MessageStatusEvent> {
        return messageStatus.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptConnectionState(connectionState: String) {
        this.connectionState.accept(connectionState)
    }

    override fun onConnectionState(): Observable<String> {
        return connectionState.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptNodeChange(nodeInfo: NodeInfo) {
        log.v("acceptNodeChange $nodeInfo")
        this.nodeChange.accept(nodeInfo)
    }

    override fun onNodeChange(): Observable<NodeInfo> {
        return nodeChange.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptDataPacket(dataPacket: DataPacket) {
        log.v("acceptDataPacket $dataPacket")
        this.dataPacket.accept(dataPacket)
    }

    override fun onDataPacket(): Observable<DataPacket> {
        return dataPacket.delay(0, TimeUnit.SECONDS, callbacks)
    }
}