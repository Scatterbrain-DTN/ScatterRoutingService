package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject

class LuidRandomizeReceiver : BroadcastReceiver() {

    private val LOG by scatterLog()

    @Inject
    lateinit var advertiser: Advertiser

    override fun onReceive(context: Context, intent: Intent) {
        val component = context.getComponent()
        if (component != null) {
            component.inject(this)
            // This method is called when the BroadcastReceiver is receiving an Intent broadcast.
            LOG.w("timer fired, randomizing luid ${advertiser.getHashLuid()}")
            advertiser.randomizeLuidAndRemove()
        }
    }
}