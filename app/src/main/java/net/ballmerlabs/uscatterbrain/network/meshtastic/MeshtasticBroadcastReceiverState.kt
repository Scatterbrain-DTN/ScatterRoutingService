package net.ballmerlabs.uscatterbrain.network.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.NodeInfo
import io.reactivex.Observable

data class MessageStatusEvent(
    val packetId: Int,
    val messageStatus: MessageStatus?
)


interface MeshtasticBroadcastReceiverState {
    fun acceptMessageStatus(messageStatus: MessageStatusEvent)
    fun acceptConnectionState(connectionState: String)
    fun acceptNodeChange(nodeInfo: NodeInfo)
    fun acceptDataPacket(dataPacket: DataPacket)
    fun onMessageStatus(): Observable<MessageStatusEvent>
    fun onConnectionState(): Observable<String>
    fun onNodeChange(): Observable<NodeInfo>
    fun onDataPacket(): Observable<DataPacket>
}