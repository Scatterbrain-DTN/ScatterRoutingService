package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
public const val SCAN_REQUEST_CODE = 44
public const val SCAN_REQUEST_LEGACY = 45

interface ScanBroadcastReceiver {
    companion object {
        @SuppressLint("UnspecifiedImmutableFlag")
        fun newPendingIntent(context: Context): PendingIntent =
            Intent(context, ScanBroadcastReceiverImpl::class.java).let {
                PendingIntent.getBroadcast(
                    context,
                    SCAN_REQUEST_CODE,
                    it,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        @SuppressLint("UnspecifiedImmutableFlag")
        fun newPendingIntentLegacy(context: Context): PendingIntent =
            Intent(context, ScanBroadcastReceiverImpl::class.java).let {
                PendingIntent.getBroadcast(
                    context,
                    SCAN_REQUEST_LEGACY,
                    it,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
    }


}