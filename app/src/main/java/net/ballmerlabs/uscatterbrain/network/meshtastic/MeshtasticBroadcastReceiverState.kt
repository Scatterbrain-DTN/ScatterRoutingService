package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.NodeInfo
import io.reactivex.Flowable

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