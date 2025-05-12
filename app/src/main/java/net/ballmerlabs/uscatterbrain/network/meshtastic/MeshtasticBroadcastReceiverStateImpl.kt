package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.NodeInfo
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import io.reactivex.Scheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

@MeshtasticConnectionScope
class MeshtasticBroadcastReceiverStateImpl @Inject constructor(
    @Named(MeshtasticConnectionSubcomponent.NamedSchedulers.CALLBACK_SCHEDULER) val callbacks: Scheduler
) : MeshtasticBroadcastReceiverState {
    private val messageStatus = PublishRelay.create<MessageStatusEvent>()
    private val connectionState = PublishRelay.create<String>()
    private val nodeChange = PublishRelay.create<NodeInfo>()
    private val dataPacket = PublishRelay.create<DataPacket>()
    override fun acceptMessageStatus(messageStatus: MessageStatusEvent) {
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
        this.nodeChange.accept(nodeInfo)
    }

    override fun onNodeChange(): Observable<NodeInfo> {
        return nodeChange.delay(0, TimeUnit.SECONDS, callbacks)
    }

    override fun acceptDataPacket(dataPacket: DataPacket) {
        this.dataPacket.accept(dataPacket)
    }

    override fun onDataPacket(): Observable<DataPacket> {
        return dataPacket.delay(0, TimeUnit.SECONDS, callbacks)
    }
}