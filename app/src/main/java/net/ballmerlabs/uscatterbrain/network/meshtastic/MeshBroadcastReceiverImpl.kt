package net.ballmerlabs.uscatterbrain.network.meshtastic
import android.content.Context
import android.content.Intent
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.NodeInfo
import javax.inject.Inject

@MeshtasticConnectionScope
class MeshBroadcastReceiverImpl @Inject constructor(

) : MeshBroadcastReceiver() {
    @Inject
    lateinit var state: MeshtasticBroadcastReceiverState

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NODE_CHANGE -> {
                val info = intent.getParcelableExtra<NodeInfo>(EXTRA_NODEINFO)
                if (info != null)
                    state.onNodeChange(info)
            }

            ACTION_MESH_CONNECTED -> {
                val connected = intent.getStringExtra(EXTRA_CONNECTED)
                if (connected != null)
                    state.onConnectionState(connected)
            }

            ACTION_MESSAGE_STATUS -> {
                val status = intent.getParcelableExtra<MessageStatus>(EXTRA_STATUS)
                val id = intent.getIntExtra(EXTRA_PACKET_ID, -1)
                state.onMessageStatus(
                    MessageStatusEvent(
                        messageStatus = status,
                        packetId = id
                    )
                )
            }
            actionReceived(PORT_NUMBER) -> {
                val payload = intent.getParcelableExtra<DataPacket>(EXTRA_PAYLOAD)
                if (payload != null)
                    state.onDataPacket(payload)
            }
        }
    }
}