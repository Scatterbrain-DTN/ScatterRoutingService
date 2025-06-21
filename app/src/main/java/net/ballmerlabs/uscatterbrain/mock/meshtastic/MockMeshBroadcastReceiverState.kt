package net.ballmerlabs.uscatterbrain.mock.meshtastic

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.NodeInfo
import io.reactivex.Flowable
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

    override fun onMessageStatus(): Flowable<MessageStatusEvent> {
        TODO("Not yet implemented")
    }

    override fun onConnectionState(): Flowable<String> {
        TODO("Not yet implemented")
    }

    override fun onNodeChange(): Flowable<NodeInfo> {
        TODO("Not yet implemented")
    }

    override fun onDataPacket(): Flowable<DataPacket> {
        TODO("Not yet implemented")
    }

}