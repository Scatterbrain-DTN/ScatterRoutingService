package net.ballmerlabs.uscatterbrain.network.meshtastic

import io.reactivex.Flowable
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.NodeInfo

data class MessageStatusEvent(
    val packetId: Int,
    val messageStatus: MessageStatus?
)


interface MeshtasticBroadcastReceiverState {
    fun acceptMessageStatus(messageStatus: MessageStatusEvent)
    fun acceptConnectionState(connectionState: String)
    fun acceptNodeChange(nodeInfo: NodeInfo)
    fun acceptDataPacket(dataPacket: DataPacket)
    fun onMessageStatus(): Flowable<MessageStatusEvent>
    fun onConnectionState(): Flowable<String>
    fun onNodeChange(): Flowable<NodeInfo>
    fun onDataPacket(): Flowable<DataPacket>
}