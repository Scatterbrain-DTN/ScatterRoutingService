package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
private const val SCAN_REQUEST_CODE = 44

interface ScanBroadcastReceiver {
    companion object {
        fun newPendingIntent(context: Context): PendingIntent =
            Intent(context, ScanBroadcastReceiverImpl::class.java).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getBroadcast(
                        context,
                        SCAN_REQUEST_CODE,
                        it,
                        PendingIntent.FLAG_MUTABLE
                    )
                } else {
                    PendingIntent.getBroadcast(context, SCAN_REQUEST_CODE, it, 0)
                }
            }
    }
}