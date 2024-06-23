package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.network.desktop.ACTION_DESKTOP_EVENT
import net.ballmerlabs.uscatterbrain.network.desktop.Broadcaster
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddr
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopAddrs
import net.ballmerlabs.uscatterbrain.network.desktop.DesktopPower
import net.ballmerlabs.uscatterbrain.network.desktop.EXTRA_APPS
import net.ballmerlabs.uscatterbrain.network.desktop.EXTRA_DESKTOP_IP
import net.ballmerlabs.uscatterbrain.network.desktop.EXTRA_DESKTOP_POWER
import net.ballmerlabs.uscatterbrain.network.desktop.EXTRA_IDENTITY_IMPORT_STATE
import net.ballmerlabs.uscatterbrain.network.desktop.IdentityImportState
import net.ballmerlabs.uscatterbrain.util.scatterLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcasterImpl @Inject constructor(
    val context: Context
): Broadcaster {

    val LOG by scatterLog()

    override fun broadcastState(
        state: IdentityImportState?,
        power: DesktopPower?,
        addr: DesktopAddrs?,
        clientApps: Boolean
    ) {
        LOG.v("broadcastPairingState ${state?.appName} $power $clientApps")
        context.sendBroadcast(Intent(ACTION_DESKTOP_EVENT).apply {
            if (state != null)
                putExtra(EXTRA_IDENTITY_IMPORT_STATE, state)
            if (power != null)
                putExtra(EXTRA_DESKTOP_POWER, power as Parcelable)
            if (addr != null)
                putExtra(EXTRA_DESKTOP_IP, addr)
            putExtra(EXTRA_APPS, clientApps)
        }, ScatterbrainApi.PERMISSION_SUPERUSER)
    }
}