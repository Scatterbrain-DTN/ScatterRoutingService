package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiDirectProviderImpl @Inject constructor(
    private val ctx: Context,
) : WifiDirectProvider {
    private val channel = AtomicReference<WifiP2pManager.Channel?>(null)
    override fun getChannel(): WifiP2pManager.Channel? {
        return channel.updateAndGet { v ->
            when (v) {
                null -> getManager()?.initialize(ctx, ctx.mainLooper, null)
                else -> v
            }
        }
    }

    override fun getManager(): WifiP2pManager? {
        return ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    }
}