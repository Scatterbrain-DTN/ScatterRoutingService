package net.ballmerlabs.uscatterbrain.mock.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.NodeInfo
import io.reactivex.Observable
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticBroadcastReceiverState
import net.ballmerlabs.uscatterbrain.network.meshtastic.MeshtasticConnectionScope
import net.ballmerlabs.uscatterbrain.network.meshtastic.MessageStatusEvent

@MeshtasticConnectionScope
class MockMeshBroadcastReceiverState :  MeshtasticBroadcastReceiverState {
    override fun acceptMessageStatus(messageStatus: MessageStatusEvent) {
        TODO("Not yet implemented")
    }

    override fun acceptConnectionState(connectionState: String) {
        TODO("Not yet implemented")
    }

    override fun acceptNodeChange(nodeInfo: NodeInfo) {
        TODO("Not yet implemented")
    }

    override fun acceptDataPacket(dataPacket: DataPacket) {
        TODO("Not yet implemented")
    }

    override fun onMessageStatus(): Observable<MessageStatusEvent> {
        TODO("Not yet implemented")
    }

    override fun onConnectionState(): Observable<String> {
        TODO("Not yet implemented")
    }

    override fun onNodeChange(): Observable<NodeInfo> {
        TODO("Not yet implemented")
    }

    override fun onDataPacket(): Observable<DataPacket> {
        TODO("Not yet implemented")
    }

}