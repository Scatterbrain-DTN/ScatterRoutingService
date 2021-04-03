package net.ballmerlabs.uscatterbrain.network.bluetoothLE

import android.util.Base64
import android.util.Log
import io.reactivex.Single
import net.ballmerlabs.uscatterbrain.network.AdvertisePacket
import net.ballmerlabs.uscatterbrain.network.LibsodiumInterface
import net.ballmerlabs.uscatterbrain.network.UpgradePacket
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectBootstrapRequest
import java.util.*

/**
 * Manages state for the FSM to generate a BootstrapRequest or
 * UpgradePacket
 */
class UpgradeStage(private val provides: AdvertisePacket.Provides) {
    private val metadata: MutableMap<String, String> = HashMap()
    private val sessionID = Random(System.nanoTime()).nextInt()

    /*
     * currently we generate our bootstrap request using hardcoded data (only wifi direct)
     * TODO: generate based on user input and/or custom plugins
     */
    private fun initMetadata() {
        when (provides) {
            AdvertisePacket.Provides.WIFIP2P -> {
                metadata.putIfAbsent(WifiDirectBootstrapRequest.KEY_NAME, WifiDirectBootstrapRequest.DEFAULT_NAME)
                val pass = ByteArray(16)
                LibsodiumInterface.sodium.randombytes_buf(pass, pass.size)
                metadata.putIfAbsent(
                        WifiDirectBootstrapRequest.KEY_PASSPHRASE,
                        Base64.encodeToString(pass, Base64.NO_WRAP or Base64.NO_PADDING)
                )
            }
            else -> {
                Log.e(TAG, "initMetadata called with invalid provides")
            }
        }
    }

    //get upgrade packet to send over network
    val upgrade: Single<UpgradePacket>
        get() = when (provides) {
            AdvertisePacket.Provides.WIFIP2P -> {
                Single.fromCallable {
                    UpgradePacket.newBuilder()
                            .setProvides(provides)
                            .setMetadata(metadata)
                            .setSessionID(sessionID)
                            .build()
                }
            }
            else -> Single.error(IllegalStateException("unsupported provides"))
        }

    companion object {
        const val TAG = "UpgradeStage"
    }

    init {
        initMetadata()
    }
}