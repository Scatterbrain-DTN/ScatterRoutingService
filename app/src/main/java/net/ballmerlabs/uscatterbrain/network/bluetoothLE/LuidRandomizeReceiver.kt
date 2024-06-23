package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.ballmerlabs.uscatterbrain.WakeLockProvider
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject

class LuidRandomizeReceiver : BroadcastReceiver() {

    private val LOG by scatterLog()

    @Inject
    lateinit var advertiser: Advertiser

    @Inject
    lateinit var broadcastReceiverState: BroadcastReceiverState

    @Inject
    lateinit var wakeLockProvider: WakeLockProvider

    override fun onReceive(context: Context, intent: Intent) {
        val component = context.getComponent()
        if (component != null) {
            component.inject(this)
            if(this::advertiser.isInitialized) {
                // This method is called when the BroadcastReceiver is receiving an Intent broadcast.
                LOG.w("timer fired, randomizing luid ${advertiser.getHashLuid()}")
                advertiser.randomizeLuidAndRemove()
                advertiser.clear(false)
                advertiser.setRandomizeTimer(30)
            } else {
                LOG.e("timer fired but advertiser was not initialized")
            }

            if(this::broadcastReceiverState.isInitialized) {
                broadcastReceiverState.killall()
            }

            if(this::wakeLockProvider.isInitialized) {
                wakeLockProvider.releaseAll()
            }
        } else {
            LOG.e("timer fired but component was null")
        }
    }
}