package net.ballmerlabs.uscatterbrain.network.meshtastic
import android.content.Context
import android.content.Intent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject

@MeshtasticConnectionScope
class MeshBroadcastReceiverImpl @Inject constructor(
    val state: MeshtasticBroadcastReceiverState
) : MeshBroadcastReceiver() {
    private val log by scatterLog()
    override fun onReceive(context: Context, intent: Intent) {

    }
}