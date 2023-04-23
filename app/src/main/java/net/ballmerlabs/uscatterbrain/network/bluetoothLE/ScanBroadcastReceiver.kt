package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
private const val SCAN_REQUEST_CODE = 44

interface ScanBroadcastReceiver {
    companion object {
        @SuppressLint("UnspecifiedImmutableFlag")
        fun newPendingIntent(context: Context): PendingIntent =
            Intent(context, ScanBroadcastReceiverImpl::class.java).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getBroadcast(
                        context,
                        SCAN_REQUEST_CODE,
                        it,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } else {
                    PendingIntent.getBroadcast(context, SCAN_REQUEST_CODE, it, PendingIntent.FLAG_UPDATE_CURRENT)
                }
            }
    }
}