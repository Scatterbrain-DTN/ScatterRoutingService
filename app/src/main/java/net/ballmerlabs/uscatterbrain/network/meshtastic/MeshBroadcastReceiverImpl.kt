package net.ballmerlabs.uscatterbrain.network.meshtastic
import android.content.Context
import android.content.Intent
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.NodeInfo
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject

@MeshtasticConnectionScope
class MeshBroadcastReceiverImpl @Inject constructor(
    val state: MeshtasticBroadcastReceiverState
) : MeshBroadcastReceiver() {
    private val log by scatterLog()
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_NODE_CHANGE -> {
                    val info = intent.getParcelableExtra<NodeInfo>(EXTRA_NODEINFO)
                    if (info != null)
                        state.acceptNodeChange(info)
                }

                ACTION_MESH_CONNECTED -> {
                    val connected = intent.getStringExtra(EXTRA_CONNECTED)
                    if (connected != null)
                        state.acceptConnectionState(connected)
                }

                ACTION_MESSAGE_STATUS -> {
                    val status = intent.getParcelableExtra<MessageStatus>(EXTRA_STATUS)
                    val id = intent.getIntExtra(EXTRA_PACKET_ID, -1)
                    state.acceptMessageStatus(
                        MessageStatusEvent(
                            messageStatus = status,
                            packetId = id
                        )
                    )
                }

                actionReceived(PORT_NUMBER) -> {
                    val payload = intent.getParcelableExtra<DataPacket>(EXTRA_PAYLOAD)
                    if (payload != null)
                        state.acceptDataPacket(payload)
                }
            }
        } catch (exc: Exception) {
            log.w("failed to receive broadcast: $exc")
        }
    }
}