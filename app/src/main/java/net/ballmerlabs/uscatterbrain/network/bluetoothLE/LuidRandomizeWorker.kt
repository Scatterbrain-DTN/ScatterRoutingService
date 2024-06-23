package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.ballmerlabs.uscatterbrain.WakeLockProvider
import net.ballmerlabs.uscatterbrain.getComponent
import net.ballmerlabs.uscatterbrain.isActive
import net.ballmerlabs.uscatterbrain.isPassive
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBroadcastReceiver
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject

class LuidRandomizeWorker(
    val context: Context,
    val workerParameters: WorkerParameters
): Worker(context, workerParameters) {

    private val LOG by scatterLog()

    @Inject
    lateinit var advertiser: Advertiser

    @Inject
    lateinit var scheduler: ScatterbrainScheduler

    @Inject
    lateinit var wifiDirectBroadcastReceiver: WifiDirectBroadcastReceiver

    @Inject
    lateinit var wakeLockProvider: WakeLockProvider

    override fun doWork(): Result {
        val component = context.getComponent()
        return if (component != null) {
            component.inject(this)
            if (!isActive(context) && !isPassive(context)) {
                LOG.w("worker disabled")
                return Result.success()
            }


            if (this::advertiser.isInitialized && this::wakeLockProvider.isInitialized &&
                this::scheduler.isInitialized && this::wifiDirectBroadcastReceiver.isInitialized) {
                // This method is called when the BroadcastReceiver is receiving an Intent broadcast.
                wakeLockProvider.hold()
                LOG.w("timer fired, randomizing luid ${advertiser.getHashLuid()}")
               // scheduler.unpauseScan()
                advertiser.randomizeLuidAndRemove()
                scheduler.unpauseScan()
                advertiser.clear(true)
                Result.success()
            } else {
                LOG.e("timer fired but advertiser was not initialized")
                Result.failure()
            }
        } else {
            LOG.e("timer fired but component was null")
            Result.failure()
        }
    }

    init {
        context.getComponent()?.inject(this)
    }

}